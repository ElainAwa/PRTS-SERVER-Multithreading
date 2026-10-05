/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Developer-only fault injection for the dispatch pipeline. It makes a batch miss its deadline in
 * a worker, and it keeps one pending pass that froze a declared world unmerged until that world
 * comes back with a new generation, so the lease refusal and the generation refusal happen on the
 * live path instead of on scratch objects.
 *
 * <p><b>Off unless declared, and not for production.</b> The only way to arm it is the JVM property
 * {@code arclight.prts.inject}; no configuration file, no reload and no command reaches it, so a
 * shipped server runs without it. It changes no default, no reading name, no rejection code and no
 * configuration key, and the two hooks below cost one boolean read while it is off.
 *
 * <p>The directive is a comma separated list: {@code delayMs=<n>} is how long a worker waits before
 * it runs a batch, {@code delayBatches=<n>} is how many batches take that wait (default one), and
 * {@code holdWorld=<id>|<id>} names the worlds one pending plan is held for. A token that cannot be
 * parsed is ignored, so a malformed directive disables the injection instead of failing a tick.
 *
 * <p>The same directive carries the faults of the ownership fixture, all zero by default, so a
 * process that declares nothing runs every row through the original path exactly once:
 * {@code ownFail=<n>} makes the first n rows fail in the worker, {@code ownDelayMs=<n>} with
 * {@code ownDelayRows=<n>} makes its first rows answer too late to be used, {@code ownThrow=<n>}
 * makes its worker throw instead of answering, {@code ownEpochBreak=<n>} fails the token
 * revalidation of the first n rows at the host entry, {@code ownWiden=<n>} admits the first n rows
 * the whole-tick model refuses, and {@code ownBreak=<n>} answers the first n rows with the captured
 * state instead of running the model. {@code ownSkipIgnored=<n>} keeps the platform from cancelling
 * the first n rows the host entry decided to skip, and {@code ownDoubleRun=<n>} runs the original
 * tick of the first n rows it decided to run a second time; the last four are the negative fixtures
 * of the equivalence harness and of the independent call counter, which have to report them.
 *
 * <p>The generation checks of the segment face carry their own faults, all zero by default:
 * {@code ownEntityBreak=<n>} fails the entity generation of the first n entries,
 * {@code ownSegmentBreak=<n>} fails the segment generation of the first n entries,
 * {@code ownOrdinalBreak=<n>} starts the first n frames as if the host had already passed their
 * first two ordinals, so the entry meets rows out of the frozen order, and
 * {@code ownObserveClaim=<n>} books a claimed row as observed as well, which the frame check of the
 * two row sets has to report.
 *
 * <p>The two ladders and the wait walk have their own tokens, all zero by default and all read on
 * the live path: {@code ladderWalk=<n>} walks the resource ladder one rung per tick,
 * {@code ladderReturn=<n>} returns it one rung per tick, and {@code waitWalk=<n>} observes n waits
 * over the bound, taking the call-site classes in turn.
 *
 * <p>The frame digest has one fault of its own, off by default: {@code segBreak=<n>} deviates one
 * row of the parallel arm's digest input in each of the first n merges, and {@code segBreakRow=<n>}
 * picks that row (one-based, default the first). Only the frame the digest folds sees the deviated
 * value, so the descent has to name that row by entity id and row offset.
 */
public final class FaultInjection {

    private static final String KEY = "arclight.prts.inject";
    private static final Spec LIVE = Spec.parse(System.getProperty(KEY));
    private static final long DELAY_MAX_MS = 5_000L;
    private static final int BATCHES_MAX = 4_096;
    private static final int ROWS_MAX = 1_000_000;
    private static final int LADDER_MAX = 8;

    private FaultInjection() {
    }

    /** Whether this process carries a directive at all. */
    public static boolean enabled() {
        return LIVE.enabled;
    }

    /** The directive of this process; the hooks read it once per call. */
    static Spec live() {
        return LIVE;
    }

    /** Waits the injected time before one worker runs a batch; a no-op while nothing is declared.
     * The caller starts its execution clock after this returns, so an injected wait never lands in
     * the worker cost row. */
    public static void pauseWorker() {
        pauseWorker(LIVE);
    }

    static void pauseWorker(Spec spec) {
        long nanos = workerDelayNanos(spec);
        if (nanos <= 0L) {
            return;
        }
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static long workerDelayNanos(Spec spec) {
        if (spec.delayNanos <= 0L || spec.delayTaken.getAndIncrement() >= spec.delayBatches) {
            return 0L;
        }
        return spec.delayNanos;
    }

    /** Whether this pending plan must stay unmerged: one of its tasks froze a declared world whose
     * generation has not changed yet, or which is between its unload and its reload. One plan is
     * held per declared world; the hold is spent when that world comes back with another
     * generation, and also when a different plan reaches the check first, so a world that was
     * already held once - including one dropped by a shutdown - is never held again. */
    public static boolean holdsMerge(WorkPlan plan, Function<String, Long> epochOf) {
        return holdsMerge(LIVE, plan, epochOf);
    }

    static boolean holdsMerge(Spec spec, WorkPlan plan, Function<String, Long> epochOf) {
        if (spec.holdWorlds.isEmpty() || plan == null) {
            return false;
        }
        for (WorkTask task : plan.tasks()) {
            String worldId = task.worldId();
            // An untracked generation (zero) names no world the store can answer for, so no hold.
            if (task.worldEpoch() <= 0L || !spec.holdWorlds.contains(worldId)
                || spec.holdSpent.contains(worldId)) {
                continue;
            }
            WorkPlan held = spec.holding.get(worldId);
            if (held != null && held != plan) {
                spec.holding.remove(worldId);
                spec.holdSpent.add(worldId);
                continue;
            }
            Long current = epochOf == null ? null : epochOf.apply(worldId);
            // A world between its unload and its reload reads negative; it is still the world the
            // pass was frozen for, so the pass keeps waiting for the reload.
            long live = current == null ? -1L : current;
            if (live < 0L || live == task.worldEpoch()) {
                spec.holding.put(worldId, plan);
                return true;
            }
            spec.holdSpent.add(worldId);
        }
        return false;
    }

    /** One parsed directive with its own counters, so a test never shares state with the process. */
    static final class Spec {

        private final boolean enabled;
        private final long delayNanos;
        private final int delayBatches;
        private final Set<String> holdWorlds;
        private final int ownFail;
        private final long ownDelayNanos;
        private final int ownDelayRows;
        private final int ownEpochBreak;
        private final int ownWiden;
        private final int ownBreak;
        private final int ownSkipIgnored;
        private final int ownDoubleRun;
        private final int ownThrow;
        private final int ownEntityBreak;
        private final int ownSegmentBreak;
        private final int ownOrdinalBreak;
        private final int ownObserveClaim;
        private final int segBreak;
        private final int segBreakRow;
        private final int ladderWalk;
        private final int ladderReturn;
        private final int waitWalk;
        private final AtomicLong delayTaken = new AtomicLong();
        private final AtomicLong ownFailTaken = new AtomicLong();
        private final AtomicLong ownDelayTaken = new AtomicLong();
        private final AtomicLong ownEpochTaken = new AtomicLong();
        private final AtomicLong ownWidenTaken = new AtomicLong();
        private final AtomicLong ownBreakTaken = new AtomicLong();
        private final AtomicLong ownSkipIgnoredTaken = new AtomicLong();
        private final AtomicLong ownDoubleRunTaken = new AtomicLong();
        private final AtomicLong ownThrowTaken = new AtomicLong();
        private final AtomicLong ownEntityBreakTaken = new AtomicLong();
        private final AtomicLong ownSegmentBreakTaken = new AtomicLong();
        private final AtomicLong ownOrdinalBreakTaken = new AtomicLong();
        private final AtomicLong ownObserveClaimTaken = new AtomicLong();
        private final AtomicLong segBreakTaken = new AtomicLong();
        private final AtomicLong ladderWalkTaken = new AtomicLong();
        private final AtomicLong ladderReturnTaken = new AtomicLong();
        private final AtomicLong waitWalkTaken = new AtomicLong();
        private final Map<String, WorkPlan> holding = new ConcurrentHashMap<>();
        private final Set<String> holdSpent = ConcurrentHashMap.newKeySet();

        private Spec(long delayNanos, int delayBatches, Set<String> holdWorlds, int ownFail,
            long ownDelayNanos, int ownDelayRows, int ownEpochBreak, int ownWiden, int ownBreak,
            int ownSkipIgnored, int ownDoubleRun, int ownThrow, int ownEntityBreak,
            int ownSegmentBreak, int ownOrdinalBreak, int ownObserveClaim, int segBreak,
            int segBreakRow, int ladderWalk, int ladderReturn, int waitWalk) {
            this.delayNanos = delayNanos;
            this.delayBatches = delayBatches;
            this.holdWorlds = Set.copyOf(holdWorlds);
            this.ownFail = ownFail;
            this.ownDelayNanos = ownDelayNanos;
            this.ownDelayRows = ownDelayRows;
            this.ownEpochBreak = ownEpochBreak;
            this.ownWiden = ownWiden;
            this.ownBreak = ownBreak;
            this.ownSkipIgnored = ownSkipIgnored;
            this.ownDoubleRun = ownDoubleRun;
            this.ownThrow = ownThrow;
            this.ownEntityBreak = ownEntityBreak;
            this.ownSegmentBreak = ownSegmentBreak;
            this.ownOrdinalBreak = ownOrdinalBreak;
            this.ownObserveClaim = ownObserveClaim;
            this.segBreak = segBreak;
            this.segBreakRow = segBreakRow;
            this.ladderWalk = ladderWalk;
            this.ladderReturn = ladderReturn;
            this.waitWalk = waitWalk;
            this.enabled = delayNanos > 0L || !holdWorlds.isEmpty() || ownFail > 0
                || ownDelayNanos > 0L || ownEpochBreak > 0 || ownWiden > 0 || ownBreak > 0
                || ownSkipIgnored > 0 || ownDoubleRun > 0 || ownThrow > 0 || ownEntityBreak > 0
                || ownSegmentBreak > 0 || ownOrdinalBreak > 0 || ownObserveClaim > 0
                || segBreak > 0 || ladderWalk > 0 || ladderReturn > 0 || waitWalk > 0;
        }

        static Spec parse(String directive) {
            long delayMs = 0L;
            int batches = 1;
            Set<String> worlds = new LinkedHashSet<>();
            int ownFail = 0;
            long ownDelayMs = 0L;
            int ownDelayRows = 1;
            int ownEpochBreak = 0;
            int ownWiden = 0;
            int ownBreak = 0;
            int ownSkipIgnored = 0;
            int ownDoubleRun = 0;
            int ownThrow = 0;
            int ownEntityBreak = 0;
            int ownSegmentBreak = 0;
            int ownOrdinalBreak = 0;
            int ownObserveClaim = 0;
            int segBreak = 0;
            int segBreakRow = 1;
            int ladderWalk = 0;
            int ladderReturn = 0;
            int waitWalk = 0;
            if (directive != null) {
                for (String token : directive.split(",")) {
                    String trimmed = token.trim();
                    int split = trimmed.indexOf('=');
                    if (split <= 0) {
                        continue;
                    }
                    String name = trimmed.substring(0, split).trim();
                    String value = trimmed.substring(split + 1).trim();
                    if ("delayMs".equals(name)) {
                        delayMs = clampNumber(value, 0L, DELAY_MAX_MS, 0L);
                    } else if ("delayBatches".equals(name)) {
                        batches = (int) clampNumber(value, 1L, BATCHES_MAX, 1L);
                    } else if ("holdWorld".equals(name)) {
                        for (String world : value.split("\\|")) {
                            if (!world.trim().isEmpty()) {
                                worlds.add(world.trim());
                            }
                        }
                    } else if ("ownFail".equals(name)) {
                        ownFail = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownDelayMs".equals(name)) {
                        ownDelayMs = clampNumber(value, 0L, DELAY_MAX_MS, 0L);
                    } else if ("ownDelayRows".equals(name)) {
                        ownDelayRows = (int) clampNumber(value, 1L, ROWS_MAX, 1L);
                    } else if ("ownEpochBreak".equals(name)) {
                        ownEpochBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownWiden".equals(name)) {
                        ownWiden = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownBreak".equals(name)) {
                        ownBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownSkipIgnored".equals(name)) {
                        ownSkipIgnored = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownDoubleRun".equals(name)) {
                        ownDoubleRun = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownThrow".equals(name)) {
                        ownThrow = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownEntityBreak".equals(name)) {
                        ownEntityBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownSegmentBreak".equals(name)) {
                        ownSegmentBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownOrdinalBreak".equals(name)) {
                        ownOrdinalBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("ownObserveClaim".equals(name)) {
                        ownObserveClaim = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("segBreak".equals(name)) {
                        segBreak = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    } else if ("segBreakRow".equals(name)) {
                        segBreakRow = (int) clampNumber(value, 1L, ROWS_MAX, 1L);
                    } else if ("ladderWalk".equals(name)) {
                        ladderWalk = (int) clampNumber(value, 0L, LADDER_MAX, 0L);
                    } else if ("ladderReturn".equals(name)) {
                        ladderReturn = (int) clampNumber(value, 0L, LADDER_MAX, 0L);
                    } else if ("waitWalk".equals(name)) {
                        waitWalk = (int) clampNumber(value, 0L, ROWS_MAX, 0L);
                    }
                }
            }
            return new Spec(delayMs * 1_000_000L, batches, worlds, ownFail, ownDelayMs * 1_000_000L,
                ownDelayRows, ownEpochBreak, ownWiden, ownBreak, ownSkipIgnored, ownDoubleRun,
                ownThrow, ownEntityBreak, ownSegmentBreak, ownOrdinalBreak, ownObserveClaim,
                segBreak, segBreakRow, ladderWalk, ladderReturn, waitWalk);
        }

        private static long clampNumber(String value, long low, long high, long fallback) {
            try {
                return Math.min(high, Math.max(low, Long.parseLong(value)));
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }

        boolean enabled() {
            return enabled;
        }

        long delayNanos() {
            return delayNanos;
        }

        int delayBatches() {
            return delayBatches;
        }

        Set<String> holdWorlds() {
            return holdWorlds;
        }

        int ownFail() {
            return ownFail;
        }

        long ownDelayNanos() {
            return ownDelayNanos;
        }

        int ownDelayRows() {
            return ownDelayRows;
        }

        int ownEpochBreak() {
            return ownEpochBreak;
        }

        int ownWiden() {
            return ownWiden;
        }

        int ownBreak() {
            return ownBreak;
        }

        int ownSkipIgnored() {
            return ownSkipIgnored;
        }

        int ownDoubleRun() {
            return ownDoubleRun;
        }

        int ownThrow() {
            return ownThrow;
        }

        int ownEntityBreak() {
            return ownEntityBreak;
        }

        int ownSegmentBreak() {
            return ownSegmentBreak;
        }

        int ownOrdinalBreak() {
            return ownOrdinalBreak;
        }

        int ownObserveClaim() {
            return ownObserveClaim;
        }

        int segBreak() {
            return segBreak;
        }

        int segBreakRow() {
            return segBreakRow;
        }

        int ladderWalk() {
            return ladderWalk;
        }

        int ladderReturn() {
            return ladderReturn;
        }

        int waitWalk() {
            return waitWalk;
        }
    }

    /** Whether the ownership row this worker is about to run must fail; off unless declared. */
    public static boolean ownershipFails() {
        return ownershipFails(LIVE);
    }

    static boolean ownershipFails(Spec spec) {
        return spec.ownFail > 0 && spec.ownFailTaken.getAndIncrement() < spec.ownFail;
    }

    /** How long the ownership row this worker is about to run waits before it answers; the wait is
     * spent outside the execution clock, so a delayed row is a row that answered too late. */
    public static long ownershipDelayNanos() {
        return ownershipDelayNanos(LIVE);
    }

    static long ownershipDelayNanos(Spec spec) {
        if (spec.ownDelayNanos <= 0L
            || spec.ownDelayTaken.getAndIncrement() >= spec.ownDelayRows) {
            return 0L;
        }
        return spec.ownDelayNanos;
    }

    /** Whether the revalidation of this host entry is forced to fail; off unless declared. */
    public static boolean ownershipEpochBreak() {
        return ownershipEpochBreak(LIVE);
    }

    static boolean ownershipEpochBreak(Spec spec) {
        return spec.ownEpochBreak > 0 && spec.ownEpochTaken.getAndIncrement() < spec.ownEpochBreak;
    }

    /** Whether this row is admitted although the model refuses it; off unless declared. */
    public static boolean ownershipWidens() {
        return ownershipWidens(LIVE);
    }

    static boolean ownershipWidens(Spec spec) {
        return spec.ownWiden > 0 && spec.ownWidenTaken.getAndIncrement() < spec.ownWiden;
    }

    /** Whether this row is answered without running the model; off unless declared. */
    public static boolean ownershipBreaks() {
        return ownershipBreaks(LIVE);
    }

    static boolean ownershipBreaks(Spec spec) {
        return spec.ownBreak > 0 && spec.ownBreakTaken.getAndIncrement() < spec.ownBreak;
    }

    /** Whether the platform is kept from cancelling a row the host entry decided to skip; off
     * unless declared. The decision stands, so the independent counter must see the row run. */
    public static boolean ownershipSkipsIgnored() {
        return ownershipSkipsIgnored(LIVE);
    }

    static boolean ownershipSkipsIgnored(Spec spec) {
        return spec.ownSkipIgnored > 0
            && spec.ownSkipIgnoredTaken.getAndIncrement() < spec.ownSkipIgnored;
    }

    /** Whether a row the host entry decided to run runs its original tick once more; off unless
     * declared, and only the independent counter of the tick body can see it. */
    public static boolean ownershipDoubleRuns() {
        return ownershipDoubleRuns(LIVE);
    }

    static boolean ownershipDoubleRuns(Spec spec) {
        return spec.ownDoubleRun > 0
            && spec.ownDoubleRunTaken.getAndIncrement() < spec.ownDoubleRun;
    }

    /** Whether the worker of a row throws instead of answering; off unless declared. The row is
     * left without an answer, which the host entry reads as a row it must run itself. */
    public static boolean ownershipThrows() {
        return ownershipThrows(LIVE);
    }

    /** Whether the entity generation of this entry is forced to fail; off unless declared. */
    public static boolean ownershipEntityBreak() {
        return ownershipEntityBreak(LIVE);
    }

    static boolean ownershipEntityBreak(Spec spec) {
        return spec.ownEntityBreak > 0 && spec.ownEntityBreakTaken.getAndIncrement() < spec.ownEntityBreak;
    }

    /** Whether the segment generation of this entry is forced to fail; off unless declared. */
    public static boolean ownershipSegmentBreak() {
        return ownershipSegmentBreak(LIVE);
    }

    static boolean ownershipSegmentBreak(Spec spec) {
        return spec.ownSegmentBreak > 0
            && spec.ownSegmentBreakTaken.getAndIncrement() < spec.ownSegmentBreak;
    }

    /** Whether the host ordinals of the first two claimed rows are swapped; off unless declared. */
    public static boolean ownershipOrdinalBreak() {
        return ownershipOrdinalBreak(LIVE);
    }

    static boolean ownershipOrdinalBreak(Spec spec) {
        return spec.ownOrdinalBreak > 0
            && spec.ownOrdinalBreakTaken.getAndIncrement() < spec.ownOrdinalBreak;
    }

    /** Whether a claimed row is booked as observed as well; off unless declared. The frame check of
     * the two row sets has to report the row it was injected for. */
    public static boolean ownershipObserveClaims() {
        return ownershipObserveClaims(LIVE);
    }

    /** The row of this frame's parallel digest input that must carry a deviated value, one-based;
     * zero while nothing is declared or after the declared number of merges has been spent. */
    public static int segmentBreakRow() {
        return segmentBreakRow(LIVE);
    }

    static int segmentBreakRow(Spec spec) {
        if (spec.segBreak <= 0 || spec.segBreakTaken.getAndIncrement() >= spec.segBreak) {
            return 0;
        }
        return spec.segBreakRow;
    }

    static boolean ownershipObserveClaims(Spec spec) {
        return spec.ownObserveClaim > 0
            && spec.ownObserveClaimTaken.getAndIncrement() < spec.ownObserveClaim;
    }

    /** How many rungs of the resource ladder this tick has to walk to, counted from one; zero while
     * nothing is declared or after the declared number of ticks. One rung is added per tick, so the
     * ladder is walked in order and a skipped rung is never the injection's doing. */
    public static int ladderWalkStep() {
        return ladderWalkStep(LIVE);
    }

    static int ladderWalkStep(Spec spec) {
        return step(spec.ladderWalk, spec.ladderWalkTaken);
    }

    /** How many rungs of the resource ladder this tick has to return, counted from one; zero while
     * nothing is declared or after the declared number of ticks. */
    public static int ladderReturnStep() {
        return ladderReturnStep(LIVE);
    }

    static int ladderReturnStep(Spec spec) {
        return step(spec.ladderReturn, spec.ladderReturnTaken);
    }

    /** How many waits over the bound this tick has to observe, and which call-site class to take
     * them at: the classes alternate, so a declaration of two walks one call site of each class. */
    public static int waitWalkStep() {
        return waitWalkStep(LIVE);
    }

    static int waitWalkStep(Spec spec) {
        if (spec.waitWalk <= 0) {
            return 0;
        }
        long taken = spec.waitWalkTaken.getAndIncrement();
        return taken >= spec.waitWalk ? 0 : (int) taken + 1;
    }

    private static int step(int declared, AtomicLong taken) {
        if (declared <= 0) {
            return 0;
        }
        long spent = taken.getAndIncrement();
        return spent >= declared ? 0 : (int) spent + 1;
    }

    static boolean ownershipThrows(Spec spec) {
        return spec.ownThrow > 0 && spec.ownThrowTaken.getAndIncrement() < spec.ownThrow;
    }
}