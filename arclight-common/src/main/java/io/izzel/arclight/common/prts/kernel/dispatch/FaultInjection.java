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
 * <p>The same directive carries the faults of the ownership fixture: {@code ownFail=<n>} makes the
 * first n ownership rows fail in the worker, {@code ownDelayMs=<n>} with {@code ownDelayRows=<n>}
 * makes its first rows answer too late to be used, {@code ownEpochBreak=<n>} fails the token
 * revalidation of the first n rows at the host entry, {@code ownWiden=<n>} admits the first n rows
 * the whole-tick model refuses, and {@code ownBreak=<n>} answers the first n rows with the captured
 * state instead of running the model. The last two are the negative fixtures of the equivalence
 * harness: they make an answer wrong on purpose so the harness can be shown to reject it.
 * {@code ownSkipIgnored=<n>} keeps the platform from cancelling the first n rows the host entry
 * decided to skip, and {@code ownDoubleRun=<n>} runs the original tick of the first n rows the host
 * entry decided to run a second time; both are the negative fixtures of the independent call
 * counter, which has to report them. All of them default to zero, so a process that declares
 * nothing runs every row through the original path exactly once.
 */
public final class FaultInjection {

    private static final String KEY = "arclight.prts.inject";
    private static final Spec LIVE = Spec.parse(System.getProperty(KEY));
    private static final long DELAY_MAX_MS = 5_000L;
    private static final int BATCHES_MAX = 4_096;
    private static final int ROWS_MAX = 1_000_000;

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
        private final AtomicLong delayTaken = new AtomicLong();
        private final AtomicLong ownFailTaken = new AtomicLong();
        private final AtomicLong ownDelayTaken = new AtomicLong();
        private final AtomicLong ownEpochTaken = new AtomicLong();
        private final AtomicLong ownWidenTaken = new AtomicLong();
        private final AtomicLong ownBreakTaken = new AtomicLong();
        private final AtomicLong ownSkipIgnoredTaken = new AtomicLong();
        private final AtomicLong ownDoubleRunTaken = new AtomicLong();
        private final Map<String, WorkPlan> holding = new ConcurrentHashMap<>();
        private final Set<String> holdSpent = ConcurrentHashMap.newKeySet();

        private Spec(long delayNanos, int delayBatches, Set<String> holdWorlds, int ownFail,
            long ownDelayNanos, int ownDelayRows, int ownEpochBreak, int ownWiden, int ownBreak,
            int ownSkipIgnored, int ownDoubleRun) {
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
            this.enabled = delayNanos > 0L || !holdWorlds.isEmpty() || ownFail > 0
                || ownDelayNanos > 0L || ownEpochBreak > 0 || ownWiden > 0 || ownBreak > 0
                || ownSkipIgnored > 0 || ownDoubleRun > 0;
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
                    }
                }
            }
            return new Spec(delayMs * 1_000_000L, batches, worlds, ownFail, ownDelayMs * 1_000_000L,
                ownDelayRows, ownEpochBreak, ownWiden, ownBreak, ownSkipIgnored, ownDoubleRun);
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
}