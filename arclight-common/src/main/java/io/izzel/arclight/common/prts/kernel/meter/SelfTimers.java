/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** The per-class self timer. Every sample is a bounded, fixed-size write into a per-thread meter:
 * no lock, no allocation and no virtual call on the recording path. */
public final class SelfTimers {

    /** Samples one class keeps per thread for the percentiles. */
    public static final int RING = 256;

    private static final ThreadLocal<ThreadMeters> METERS = ThreadLocal.withInitial(ThreadMeters::new);
    private static final List<ThreadMeters> ALL = new CopyOnWriteArrayList<>();
    private static final WaitCounters WAITS = new WaitCounters();

    private SelfTimers() {
    }

    /** A metering scope: the class, the world and the decomposition reference of one batch. */
    public record MeterScope(SelfClass selfClass, String worldId, String dimensionRef) {
    }

    /** Opens a metering scope. */
    public static MeterScope open(SelfClass selfClass, String worldId, String dimensionRef) {
        return new MeterScope(selfClass, worldId, dimensionRef == null ? "" : dimensionRef);
    }

    /** Closes a scope and records its duration. */
    public static void close(MeterScope scope, long nanos) {
        note(scope.selfClass(), scope.worldId(), scope.dimensionRef(), nanos);
    }

    /** Records one batch without a scope object. */
    public static void note(SelfClass selfClass, String worldId, String dimensionRef, long nanos) {
        if (nanos <= 0L || selfClass == null) {
            return;
        }
        METERS.get().meter(selfClass, worldId == null ? "" : worldId).add(nanos);
    }

    /** Records a wait next to the classes. The value lands in the wait counters only; no self
     * class receives it, which is the separation the whole axis depends on. */
    public static void noteWait(String worldId, String dimensionRef, String waitPointId, long nanos) {
        WAITS.add(worldId == null ? "" : worldId, nanos);
    }

    /** Publishes the window without clearing it. */
    public static MeterWindow snapshot(long tickIndex, int windowTicks, boolean warmup) {
        return collect(tickIndex, windowTicks, warmup, false);
    }

    /** Publishes the window and starts a new one. */
    public static MeterWindow consume(long tickIndex, int windowTicks, boolean warmup) {
        return collect(tickIndex, windowTicks, warmup, true);
    }

    /** Takes the per-world tick totals and clears them. The share table needs the work of one tick
     * per world and class; the timer keeps those sums next to the window sums so no second
     * metering point appears. */
    public static Map<String, long[]> consumeTickTotals() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (ThreadMeters meters : ALL) {
            for (WorldMeters world : meters.worlds) {
                long[] row = totals.computeIfAbsent(world.worldId,
                    key -> new long[SelfClass.values().length]);
                for (int index = 0; index < world.meters.length; index++) {
                    row[index] += world.meters[index].tickNanos();
                    world.meters[index].clearTick();
                }
            }
        }
        return totals;
    }

    /** Callers use this while the share table is off: the timer keeps adding to the tick totals on
     * its recording path, and a table that is turned back on must not inherit the ticks of the
     * whole time it was off as the work of one tick. */
    public static void discardTickTotals() {
        for (ThreadMeters meters : ALL) {
            for (WorldMeters world : meters.worlds) {
                for (SelfTimer meter : world.meters) {
                    meter.clearTick();
                }
            }
        }
    }

    /** Clears every counter. */
    public static void resetAll() {
        for (ThreadMeters meters : ALL) {
            for (WorldMeters world : meters.worlds) {
                for (SelfTimer meter : world.meters) {
                    meter.clear();
                }
            }
        }
        WAITS.clear();
    }

    public static int timerRows() {
        return SelfClass.rowCount();
    }

    private static MeterWindow collect(long tickIndex, int windowTicks, boolean warmup,
                                       boolean reset) {
        SelfClass[] classes = SelfClass.values();
        long[] totals = new long[classes.length];
        long[] recorded = new long[classes.length];
        long[] lost = new long[classes.length];
        int[] retained = new int[classes.length];
        for (ThreadMeters meters : ALL) {
            for (WorldMeters world : meters.worlds) {
                for (int index = 0; index < classes.length; index++) {
                    SelfTimer meter = world.meters[index];
                    totals[index] += meter.windowNanos();
                    recorded[index] += meter.recorded();
                    lost[index] += meter.lost();
                    retained[index] += meter.retained();
                }
            }
        }
        long windowTotal = 0L;
        for (long total : totals) {
            windowTotal += total;
        }
        List<SelfRow> rows = new ArrayList<>(classes.length);
        for (int index = 0; index < classes.length; index++) {
            long[] samples = gather(classes[index], retained[index]);
            Arrays.sort(samples);
            long p50 = percentile(samples, 0.50);
            long p99 = percentile(samples, 0.99);
            double share = windowTotal == 0L ? 0.0 : 100.0 * totals[index] / windowTotal;
            rows.add(new SelfRow(classes[index], totals[index], share, p50, p99,
                samples.length, lost[index]));
        }
        // A row published with a zero value is complete; the count only marks a class the
        // publication could not describe at all.
        int missing = classes.length - rows.size();
        long recordedTotal = 0L;
        long retainedTotal = 0L;
        long lostTotal = 0L;
        for (int index = 0; index < classes.length; index++) {
            recordedTotal += recorded[index];
            retainedTotal += retained[index];
            lostTotal += lost[index];
        }
        double sampleRate = recordedTotal == 0L ? 1.0 : (double) retainedTotal / recordedTotal;
        double observeMs = totals[SelfClass.OBSERVE.ordinal()] / 1_000_000.0;
        double unclassifiedMs = totals[SelfClass.OTHER.ordinal()] / 1_000_000.0;
        double totalMs = windowTotal / 1_000_000.0;
        long waitTotalNanos = WAITS.totalNanos.get();
        long waitMaxNanos = WAITS.maxNanos.get();
        long waitObservations = WAITS.observations.sum();
        if (reset) {
            for (ThreadMeters meters : ALL) {
                for (WorldMeters world : meters.worlds) {
                    for (SelfTimer meter : world.meters) {
                        meter.clearWindow();
                    }
                }
            }
            WAITS.clear();
        }
        return new MeterWindow(tickIndex, windowTicks, warmup, List.copyOf(rows), missing,
            sampleRate, lostTotal, observeMs, unclassifiedMs, totalMs,
            waitTotalNanos / 1_000_000.0, waitMaxNanos / 1_000_000.0, waitObservations);
    }

    private static long[] gather(SelfClass selfClass, int capacity) {
        long[] samples = new long[Math.max(0, capacity)];
        int position = 0;
        for (ThreadMeters meters : ALL) {
            for (WorldMeters world : meters.worlds) {
                position += world.meters[selfClass.ordinal()].copySamples(samples, position);
            }
        }
        return samples;
    }

    private static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(fraction * sorted.length);
        int index = Math.max(0, Math.min(sorted.length - 1, rank - 1));
        return sorted[index];
    }

    private static final class ThreadMeters {

        private final List<WorldMeters> worlds = new CopyOnWriteArrayList<>();

        private ThreadMeters() {
            ALL.add(this);
        }

        private SelfTimer meter(SelfClass selfClass, String worldId) {
            for (int index = 0; index < worlds.size(); index++) {
                WorldMeters world = worlds.get(index);
                if (world.worldId.equals(worldId)) {
                    return world.meters[selfClass.ordinal()];
                }
            }
            WorldMeters created = new WorldMeters(worldId);
            worlds.add(created);
            return created.meters[selfClass.ordinal()];
        }
    }

    private static final class WorldMeters {

        private final String worldId;
        private final SelfTimer[] meters = new SelfTimer[SelfClass.values().length];

        private WorldMeters(String worldId) {
            this.worldId = worldId;
            for (int index = 0; index < meters.length; index++) {
                meters[index] = new SelfTimer(RING);
            }
        }
    }

    private static final class WaitCounters {

        private final Map<String, LongAdder> byWorld = new ConcurrentHashMap<>();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final LongAdder observations = new LongAdder();

        private void add(String worldId, long nanos) {
            if (nanos <= 0L) {
                return;
            }
            byWorld.computeIfAbsent(worldId, key -> new LongAdder()).add(nanos);
            totalNanos.addAndGet(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
            observations.increment();
        }

        private void clear() {
            byWorld.clear();
            totalNanos.set(0L);
            maxNanos.set(0L);
            observations.reset();
        }
    }

    /** One published timer row. The row exists for every class even when nothing was sampled: a
     * missing row is an observation failure, and a row of zeros is a statement. */
    public record SelfRow(SelfClass selfClass, long totalNanos, double sharePct, long p50Nanos,
                          long p99Nanos, long samples, long lost) {

        public double totalMs() {
            return totalNanos / 1_000_000.0;
        }

        public double p50Ms() {
            return p50Nanos / 1_000_000.0;
        }

        public double p99Ms() {
            return p99Nanos / 1_000_000.0;
        }
    }

    /** The window carries the rows, the completeness count and the three self-monitoring values that
     * prove the observation is not the bottleneck: the sample rate actually achieved, the number of
     * samples the rings had to drop and the time observation itself spent. */
    public record MeterWindow(long tickIndex, int windowTicks, boolean warmup, List<SelfRow> rows,
                              int missingClasses, double sampleRate, long lostSamples, double observeMs,
                              double unclassifiedMs, double totalMs, double waitTotalMs,
                              double waitMaxMs, long waitObservations) {

        /** Finds one row. */
        public SelfRow row(SelfClass selfClass) {
            for (SelfRow row : rows) {
                if (row.selfClass() == selfClass) {
                    return row;
                }
            }
            return null;
        }

        public int rowCount() {
            return rows.size();
        }
    }
}
