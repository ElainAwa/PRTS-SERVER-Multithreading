/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import io.izzel.arclight.common.prts.PrtsSwitches;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/** Settings of the kernel scaffolding layer; the names and their defaults live in the configuration layer. */
public final class KernelSettings {

    public static final String CATEGORY = PrtsConfigManager.KERNEL;

    /** Turns the write decision point into a refusing one for unregistered writers; off. */
    public static final String ENFORCE_UNREGISTERED_WRITES = "enforce-unregistered-writes";

    public static final String SELF_TIMERS = "self-timers";

    public static final String SHARE_TABLE = "share-table";

    public static final String WAIT_REGISTRY = "wait-registry";

    public static final String WRITE_PATH_GUARD = "write-path-guard";

    /** Walks the intent channel and applies what it reaches; off, so nothing is consumed. */
    public static final String COMMIT_INTENTS = "commit-intents";

    /** Hands an undeclared write to the intent channel instead of only recording it; off. */
    public static final String ROUTE_UNREGISTERED_WRITES = "route-unregistered-writes";

    /** Length of one self-metering window, in seconds; never shorter than ten minutes. */
    public static final String SELF_WINDOW_SECONDS = "self-window-seconds";

    /** Leading part of a window that is published as warm-up and excluded from verdicts. */
    public static final String SELF_WARMUP_SECONDS = "self-warmup-seconds";

    /** Time one tick may spend in total, before the host overhead and the reserve. */
    public static final String E_BUDGET_MS = "e-budget-ms";

    /** Fair share one world receives per tick, split over the class rows. */
    public static final String WORLD_SHARE_MS = "world-share-ms";

    /** Single-column pool only the two reserved entries may draw from. */
    public static final String RESERVE_MS = "reserve-ms";

    /** Fixed part of the budget the classes may never borrow. */
    public static final String HOST_OVERHEAD_MS = "host-overhead-ms";

    /** Depth at which one world's intent shard refuses instead of queueing. */
    public static final String INTENT_QUEUE_CAP = "intent-queue-cap";

    /** How many intents one tick's commit walk may reach. */
    public static final String COMMIT_BUDGET = "commit-budget";

    /** Upper bound of one wait, in milliseconds. */
    public static final String WAIT_BOUND_MS = "wait-bound-ms";

    /** Answers a wait no row covers with a refusal code; off, such a wait is only counted. */
    public static final String REFUSE_UNREGISTERED_WAITS = "refuse-unregistered-waits";

    /** Lets a rung of the resource ladder execute its action; off, a rung that was reached only
     * publishes its counters. */
    public static final String DEGRADE_ACTIONS = "degrade-actions";

    /** Run of clean ticks a rung needs before it may return; a declared value, not a measured one. */
    public static final String DEGRADE_ROLLBACK_TICKS = "degrade-rollback-ticks";

    /** Number of retries one attempt carries before a refusal is final. */
    public static final String RETRY_BUDGET = "retry-budget";

    /** Turns the parallel dispatch of the first domain on; off, so nothing is dispatched. */
    public static final String DISPATCH_PARALLEL = "dispatch-parallel";

    /** Narrows the write-back to the kinematics the host itself already holds; off. */
    public static final String DISPATCH_IDENTICAL_ONLY = "dispatch-identical-only";

    /** Lets the write-back own the kinematics it lands; off, so the domain only computes. */
    public static final String DISPATCH_TAKEOVER = "dispatch-takeover";

    /** How many worker threads the pool holds; zero means derive it from the machine. */
    public static final String WORKER_COUNT = "worker-count";

    /** How many batches may wait in the pool before the tick thread takes over. */
    public static final String WORKER_QUEUE_CAP = "worker-queue-cap";

    /** How many chunks one region covers on a side, the span of one batch. */
    public static final String WORKER_BATCH_CHUNKS = "worker-batch-chunks";

    /** How long the merge waits past the tick boundary before it cancels. */
    public static final String WORKER_DEADLINE_GRACE_MS = "worker-deadline-grace-ms";

    /** How many retryable faults a worker attempt may carry before it falls back. */
    public static final String WORKER_RETRY_BUDGET = "worker-retry-budget";

    public static final int INTENT_QUEUE_CAP_MAX = 65536;

    public static final int COMMIT_BUDGET_MAX = 4096;

    public static final int WORKER_COUNT_MAX = 8;

    public static final int WORKER_QUEUE_CAP_MAX = 256;

    public static final int WORKER_BATCH_CHUNKS_MAX = 64;

    public static final int WORKER_DEADLINE_GRACE_MS_MAX = 1000;

    public static final int WORKER_RETRY_BUDGET_MAX = 2;

    private KernelSettings() {
    }

    /** A piece of the kernel can be gated by a category other than its own - the call-site seams
     * live in the correctness-fixes category - so a reader that wants to know whether such a seam
     * is even in the bytecode asks the category switch through here. */
    public static boolean categoryEnabled(String category) {
        try {
            return PrtsSwitches.enabled(category);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean enabled() {
        try {
            return PrtsSwitches.enabled(CATEGORY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean enforceUnregisteredWrites() {
        return feature(ENFORCE_UNREGISTERED_WRITES);
    }

    public static boolean selfTimers() {
        return feature(SELF_TIMERS);
    }

    public static boolean shareTable() {
        return feature(SHARE_TABLE);
    }

    public static boolean waitRegistry() {
        return feature(WAIT_REGISTRY);
    }

    public static boolean refuseUnregisteredWaits() {
        return feature(REFUSE_UNREGISTERED_WAITS);
    }

    public static boolean degradeActions() {
        return feature(DEGRADE_ACTIONS);
    }

    public static int degradeRollbackTicks() {
        return clamp(number(DEGRADE_ROLLBACK_TICKS), 1, 600);
    }

    public static boolean writePathGuard() {
        return feature(WRITE_PATH_GUARD);
    }

    public static boolean commitIntents() {
        return feature(COMMIT_INTENTS);
    }

    public static boolean routeUnregisteredWrites() {
        return feature(ROUTE_UNREGISTERED_WRITES);
    }

    public static long selfWindowTicks() {
        return 20L * number(SELF_WINDOW_SECONDS);
    }

    public static long selfWarmupTicks() {
        return 20L * number(SELF_WARMUP_SECONDS);
    }

    public static double eBudgetMs() {
        return number(E_BUDGET_MS);
    }

    public static double worldShareMs() {
        return number(WORLD_SHARE_MS);
    }

    public static double reserveMs() {
        return number(RESERVE_MS);
    }

    public static double hostOverheadMs() {
        return number(HOST_OVERHEAD_MS);
    }

    public static int intentQueueCap() {
        return clamp(number(INTENT_QUEUE_CAP), 1, INTENT_QUEUE_CAP_MAX);
    }

    public static int commitBudget() {
        return clamp(number(COMMIT_BUDGET), 1, COMMIT_BUDGET_MAX);
    }

    /** Clamps one whole-number setting into its accepted range. The configuration layer already
     * clamps what it reads against the declared range; this is the second bound, at the point a
     * piece of the kernel turns the setting into work. */
    public static int clamp(int value, int low, int high) {
        return Math.min(high, Math.max(low, value));
    }

    public static int waitBoundMs() {
        return number(WAIT_BOUND_MS);
    }

    public static int retryBudget() {
        return number(RETRY_BUDGET);
    }

    public static boolean dispatchParallel() {
        return feature(DISPATCH_PARALLEL);
    }

    /** On, the leg lands nothing it cannot prove the host path itself produced: a row whose
     * position, orientation and velocity are bit-identical to the world's own values is counted as
     * taken over and left untouched (writing it would be the same value through a setter with side
     * effects), and a row that differs is counted and stays with the host path. */
    public static boolean dispatchIdenticalOnly() {
        return feature(DISPATCH_IDENTICAL_ONLY);
    }

    /** Answers whether the write-back may own the kinematics it lands. Off, the domain only
     * computes: the merge settles each batch by reading the world back in the same tick, counting
     * how many rows the host path itself produced, and landing nothing, so every state change
     * stays with the host. */
    public static boolean dispatchTakeover() {
        return feature(DISPATCH_TAKEOVER);
    }

    public static int workerCountDeclared() {
        return number(WORKER_COUNT);
    }

    public static int workerQueueCap() {
        return clamp(number(WORKER_QUEUE_CAP), 1, WORKER_QUEUE_CAP_MAX);
    }

    public static int workerBatchChunks() {
        return clamp(number(WORKER_BATCH_CHUNKS), 1, WORKER_BATCH_CHUNKS_MAX);
    }

    public static int workerDeadlineGraceMs() {
        return clamp(number(WORKER_DEADLINE_GRACE_MS), 0, WORKER_DEADLINE_GRACE_MS_MAX);
    }

    public static int workerRetryBudget() {
        return clamp(number(WORKER_RETRY_BUDGET), 0, WORKER_RETRY_BUDGET_MAX);
    }

    private static boolean feature(String name) {
        try {
            return PrtsConfigManager.feature(CATEGORY, name);
        } catch (Throwable ignored) {
            return declaredFeature(name);
        }
    }

    private static int number(String name) {
        try {
            return PrtsConfigManager.number(CATEGORY, name);
        } catch (Throwable ignored) {
            return declaredNumber(name);
        }
    }

    private static boolean declaredFeature(String name) {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(CATEGORY);
        Boolean value = entry == null ? null : entry.features().get(name);
        if (value == null) {
            throw new IllegalArgumentException("the kernel category declares no feature " + name);
        }
        return value;
    }

    private static int declaredNumber(String name) {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(CATEGORY);
        PrtsConfigManager.IntSetting setting = entry == null ? null : entry.numbers().get(name);
        if (setting == null) {
            throw new IllegalArgumentException("the kernel category declares no setting " + name);
        }
        return setting.defaultValue();
    }
}
