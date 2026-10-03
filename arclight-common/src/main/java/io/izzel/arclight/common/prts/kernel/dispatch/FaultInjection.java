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
 */
public final class FaultInjection {

    private static final String KEY = "arclight.prts.inject";
    private static final Spec LIVE = Spec.parse(System.getProperty(KEY));
    private static final long DELAY_MAX_MS = 5_000L;
    private static final int BATCHES_MAX = 4_096;

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
        private final AtomicLong delayTaken = new AtomicLong();
        private final Map<String, WorkPlan> holding = new ConcurrentHashMap<>();
        private final Set<String> holdSpent = ConcurrentHashMap.newKeySet();

        private Spec(long delayNanos, int delayBatches, Set<String> holdWorlds) {
            this.delayNanos = delayNanos;
            this.delayBatches = delayBatches;
            this.holdWorlds = Set.copyOf(holdWorlds);
            this.enabled = delayNanos > 0L || !holdWorlds.isEmpty();
        }

        static Spec parse(String directive) {
            long delayMs = 0L;
            int batches = 1;
            Set<String> worlds = new LinkedHashSet<>();
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
                    }
                }
            }
            return new Spec(delayMs * 1_000_000L, batches, worlds);
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
    }
}