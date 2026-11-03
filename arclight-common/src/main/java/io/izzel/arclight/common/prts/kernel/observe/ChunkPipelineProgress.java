/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * The internal per-world progress face of the chunk generation/light pipeline. A completion arrives
 * from the real {@code ChunkMap.applyStep} future, after it has finished; the face keeps a bounded
 * per-world/tick count of successful steps and refuses failures, cancellations, duplicates, stale
 * epochs, unknown worlds and a full buffer. It publishes no readout key and changes no future,
 * ordering or dispatch: the callback is observation only and a report that cannot be kept is
 * counted, not thrown.
 */
public final class ChunkPipelineProgress {

    /** The largest number of worlds the internal face keeps. */
    public static final int MAX_WORLDS = 256;

    /** The largest number of tick groups one world keeps. */
    public static final int MAX_TICK_GROUPS = 128;

    private static final int MAX_RECENT_TICKETS = 512;

    /** What happened to one reported completion. */
    public enum Outcome {
        COUNTED,
        FAILED,
        CANCELLED,
        MISSING_RESULT,
        DUPLICATE,
        STALE_EPOCH,
        UNTRACKED_EPOCH,
        WORLD_CAPACITY,
        STALE_TICK
    }

    /** One retained tick of one world: the completed generation, light and send-ready steps. */
    public record TickCounts(long tick, long genSteps, long lightSteps, long sendSteps) {
    }

    /** One world's bounded progress view: real epoch, cumulative progress and retained ticks. */
    public record WorldReading(String worldId, long epoch, long genSteps, long lightSteps,
                               long sendSteps, List<TickCounts> ticks) {
    }

    /** An immutable read of the internal face; it is consumed by tests/agents, not by a readout key. */
    public record Snapshot(boolean installed, List<WorldReading> worlds,
                           Map<Outcome, Long> outcomes) {
    }

    private static final Object LOCK = new Object();
    private static final AtomicLong TICKETS = new AtomicLong();
    private static final Map<String, WorldState> WORLDS = new LinkedHashMap<>(16, 0.75f, true);
    private static final EnumMap<Outcome, LongAdder> OUTCOMES = new EnumMap<>(Outcome.class);

    private static volatile boolean installed;
    private static ToLongFunction<String> epochOf = world -> WorldEpochs.UNTRACKED;
    private static LongSupplier tickIndex = () -> 0L;

    static {
        for (Outcome outcome : Outcome.values()) {
            OUTCOMES.put(outcome, new LongAdder());
        }
    }

    private ChunkPipelineProgress() {
    }

    /** Installs or removes the face. It is installed only while the kernel and the B01-A child meter
     * are both on; an install while already installed keeps the retained counters and refreshes the
     * suppliers, a disable clears them. */
    public static void install(boolean wanted, ToLongFunction<String> epochs, LongSupplier ticks) {
        synchronized (LOCK) {
            if (!wanted) {
                installed = false;
                clearLocked();
                return;
            }
            if (epochs != null) {
                epochOf = epochs;
            }
            if (ticks != null) {
                tickIndex = ticks;
            }
            if (!installed) {
                clearLocked();
                installed = true;
            }
        }
    }

    /** Whether the face is installed. */
    public static boolean installed() {
        return installed;
    }

    /** Mints the identity of one reported completion; the callback keeps it for duplicate refusal. */
    public static long nextTicket() {
        return TICKETS.incrementAndGet();
    }

    /** Returns the epoch the real world-lifecycle source assigns to a world. */
    public static long epochOf(String worldId) {
        ToLongFunction<String> source = epochOf;
        try {
            return source.applyAsLong(worldId);
        } catch (Throwable unreadable) {
            return WorldEpochs.UNTRACKED;
        }
    }

    /** Reports one completed generation/light step. The callback observes only: a failure, a
     * cancellation, a missing result, a duplicate ticket, a stale or absent epoch, an unknown world
     * and a full bound are answers in the outcome table, never exceptions to the future. */
    public static void note(long ticket, String worldId, long epochAtStart, long chunkPos,
                            boolean light, Throwable failure, boolean resultPresent) {
        note(ticket, worldId, epochAtStart, chunkPos, light, failure, resultPresent, false);
    }

    /** Reports one real send-ready transition. The caller observed the host's own send-ready
     * notification, so there is no failure/cancel result here; a transition that cannot be kept is
     * refused into the same outcome table and never reaches the send path. */
    public static void noteSend(String worldId, long epochAtStart, long chunkPos) {
        note(0L, worldId, epochAtStart, chunkPos, false, null, true, true);
    }

    private static void note(long ticket, String worldId, long epochAtStart, long chunkPos,
                             boolean light, Throwable failure, boolean resultPresent,
                             boolean send) {
        if (!installed) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (failure != null) {
                    count(failure instanceof java.util.concurrent.CancellationException
                        ? Outcome.CANCELLED : Outcome.FAILED);
                    return;
                }
                if (!resultPresent) {
                    count(Outcome.MISSING_RESULT);
                    return;
                }
                long currentEpoch = epochSource(worldId);
                if (epochAtStart <= WorldEpochs.UNTRACKED) {
                    count(Outcome.UNTRACKED_EPOCH);
                    return;
                }
                if (currentEpoch != epochAtStart) {
                    count(Outcome.STALE_EPOCH);
                    return;
                }
                long tick = safeTick();
                if (tick < 0L) {
                    count(Outcome.STALE_TICK);
                    return;
                }
                WorldState world = WORLDS.get(worldId);
                if (world == null) {
                    if (WORLDS.size() >= MAX_WORLDS) {
                        count(Outcome.WORLD_CAPACITY);
                        return;
                    }
                    world = new WorldState(worldId);
                    WORLDS.put(worldId, world);
                }
                if (world.epoch != currentEpoch) {
                    world.epoch = currentEpoch;
                    world.clearSendHistory();
                }
                if (send ? world.sendDuplicate(chunkPos) : world.duplicate(ticket)) {
                    count(Outcome.DUPLICATE);
                    return;
                }
                if (!world.acceptTick(tick)) {
                    count(Outcome.STALE_TICK);
                    return;
                }
                world.count(tick, light, send);
                count(Outcome.COUNTED);
            }
        } catch (Throwable ignored) {
            count(Outcome.FAILED);
        }
    }

    /** Removes one world's retained progress, as an unload does. */
    public static void forgetWorld(String worldId) {
        if (worldId == null) {
            return;
        }
        synchronized (LOCK) {
            WORLDS.remove(worldId);
        }
    }

    /** Clears every retained count but keeps the install state and suppliers. */
    public static void reset() {
        synchronized (LOCK) {
            clearLocked();
        }
    }

    /** Returns an immutable bounded snapshot for a test or a controlled-window reader. */
    public static Snapshot snapshot() {
        synchronized (LOCK) {
            List<WorldReading> worlds = new ArrayList<>(WORLDS.size());
            for (WorldState world : WORLDS.values()) {
                worlds.add(world.read());
            }
            Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
            for (Map.Entry<Outcome, LongAdder> entry : OUTCOMES.entrySet()) {
                outcomes.put(entry.getKey(), entry.getValue().sum());
            }
            return new Snapshot(installed, List.copyOf(worlds), Map.copyOf(outcomes));
        }
    }

    private static long epochSource(String worldId) {
        try {
            return epochOf.applyAsLong(worldId);
        } catch (Throwable unreadable) {
            return WorldEpochs.UNTRACKED;
        }
    }

    private static long safeTick() {
        try {
            return tickIndex.getAsLong();
        } catch (Throwable unreadable) {
            return -1L;
        }
    }

    private static void count(Outcome outcome) {
        LongAdder cell = OUTCOMES.get(outcome);
        if (cell != null) {
            cell.increment();
        }
    }

    private static void clearLocked() {
        WORLDS.clear();
        for (LongAdder cell : OUTCOMES.values()) {
            cell.reset();
        }
    }

    private static final class WorldState {

        private final String worldId;
        private final TreeMap<Long, TickCounts> ticks = new TreeMap<>();
        private final ArrayDeque<Long> recentTickets = new ArrayDeque<>();
        private final Set<Long> recentTicketSet = new HashSet<>();
        private final ArrayDeque<Long> recentSendChunks = new ArrayDeque<>();
        private final Set<Long> recentSendChunkSet = new HashSet<>();
        private long epoch = WorldEpochs.UNTRACKED;
        private long genSteps;
        private long lightSteps;
        private long sendSteps;

        private WorldState(String worldId) {
            this.worldId = worldId;
        }

        private boolean duplicate(long ticket) {
            if (!recentTicketSet.add(ticket)) {
                return true;
            }
            recentTickets.addLast(ticket);
            while (recentTickets.size() > MAX_RECENT_TICKETS) {
                Long old = recentTickets.removeFirst();
                recentTicketSet.remove(old);
            }
            return false;
        }

        private boolean sendDuplicate(long chunkPos) {
            if (!recentSendChunkSet.add(chunkPos)) {
                return true;
            }
            recentSendChunks.addLast(chunkPos);
            while (recentSendChunks.size() > MAX_RECENT_TICKETS) {
                Long old = recentSendChunks.removeFirst();
                recentSendChunkSet.remove(old);
            }
            return false;
        }

        private void clearSendHistory() {
            recentSendChunks.clear();
            recentSendChunkSet.clear();
        }

        private boolean acceptTick(long tick) {
            if (ticks.isEmpty()) {
                return true;
            }
            long oldest = ticks.firstKey();
            if (tick < oldest) {
                return false;
            }
            long newest = ticks.lastKey();
            if (tick > newest) {
                long floor = tick - MAX_TICK_GROUPS + 1L;
                while (!ticks.isEmpty() && ticks.firstKey() < floor) {
                    ticks.remove(ticks.firstKey());
                }
            }
            return ticks.size() < MAX_TICK_GROUPS || ticks.containsKey(tick);
        }

        private void count(long tick, boolean light, boolean send) {
            TimedCounts current = new TimedCounts(ticks.get(tick));
            if (send) {
                current.send++;
                sendSteps++;
            } else if (light) {
                current.light++;
                lightSteps++;
            } else {
                current.gen++;
                genSteps++;
            }
            ticks.put(tick, current.toRecord(tick));
        }

        private WorldReading read() {
            List<TickCounts> copy = new ArrayList<>(ticks.size());
            for (Map.Entry<Long, TickCounts> entry : ticks.entrySet()) {
                copy.add(entry.getValue());
            }
            return new WorldReading(worldId, epoch, genSteps, lightSteps, sendSteps,
                List.copyOf(copy));
        }
    }

    private static final class TimedCounts {

        private long gen;
        private long light;
        private long send;

        private TimedCounts(TickCounts previous) {
            if (previous != null) {
                this.gen = previous.genSteps();
                this.light = previous.lightSteps();
                this.send = previous.sendSteps();
            }
        }

        private TickCounts toRecord(long tick) {
            return new TickCounts(tick, gen, light, send);
        }
    }
}
