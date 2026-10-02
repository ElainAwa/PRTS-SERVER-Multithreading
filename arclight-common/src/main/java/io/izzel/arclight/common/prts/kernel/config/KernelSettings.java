/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import io.izzel.arclight.common.prts.PrtsSwitches;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/**
 * Settings of the kernel scaffolding layer.
 *
 * <p>The names and the defaults live in the configuration layer, which is the only place a
 * generated file is rendered from. This class only names the keys the four pieces read, so a
 * default is never repeated and cannot drift away from the generated file.</p>
 *
 * <p>Every accessor falls back to the declared default when the configuration layer cannot answer:
 * the mixin plugins resolve categories before the configuration directory has been read, and a
 * broken file must not turn a category on by accident.</p>
 *
 * <p>The category itself is off by default, so nothing in this package runs unless an operator
 * turns the kernel category on (or overrides it with the category system property). Every feature
 * below is read at the moment its piece runs, so a configuration reload applies without a
 * restart.</p>
 */
public final class KernelSettings {

    /** Category the four pieces belong to; also the configuration file they are declared in. */
    public static final String CATEGORY = PrtsConfigManager.KERNEL;

    /** Turns the write decision point into a refusing one for unregistered writers; off. */
    public static final String ENFORCE_UNREGISTERED_WRITES = "enforce-unregistered-writes";

    /** Turns the per-class self timers on; on with the category. */
    public static final String SELF_TIMERS = "self-timers";

    /** Turns the per-tick share table on; on with the category. */
    public static final String SHARE_TABLE = "share-table";

    /** Turns the wait point registry on; on with the category. */
    public static final String WAIT_REGISTRY = "wait-registry";

    /** Watches the real world write paths; on with the category, and it only records. */
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

    /** Number of retries one attempt carries before a refusal is final. */
    public static final String RETRY_BUDGET = "retry-budget";

    /** Turns the parallel dispatch of the first domain on; off, so nothing is dispatched. */
    public static final String DISPATCH_PARALLEL = "dispatch-parallel";

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

    /** Upper bound the channel depth is clamped to, whatever the file says. */
    public static final int INTENT_QUEUE_CAP_MAX = 65536;

    /** Upper bound the commit budget is clamped to, whatever the file says. */
    public static final int COMMIT_BUDGET_MAX = 4096;

    /** Upper bound the declared worker count is clamped to, whatever the file says. */
    public static final int WORKER_COUNT_MAX = 8;

    /** Upper bound the worker queue depth is clamped to, whatever the file says. */
    public static final int WORKER_QUEUE_CAP_MAX = 256;

    /** Upper bound the region span is clamped to, whatever the file says. */
    public static final int WORKER_BATCH_CHUNKS_MAX = 64;

    /** Upper bound the deadline grace is clamped to, whatever the file says. */
    public static final int WORKER_DEADLINE_GRACE_MS_MAX = 1000;

    /** Upper bound the worker retry budget is clamped to, whatever the file says. */
    public static final int WORKER_RETRY_BUDGET_MAX = 2;

    private KernelSettings() {
    }

    /**
     * Returns whether one PRTS category is enabled.
     *
     * <p>A piece of the kernel can be gated by a category other than its own - the call-site seams
     * live in the correctness-fixes category - so a reader that wants to know whether such a seam is
     * even in the bytecode asks the category switch through here.</p>
     *
     * @param category the category name
     * @return {@code true} when that category is enabled
     */
    public static boolean categoryEnabled(String category) {
        try {
            return PrtsSwitches.enabled(category);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns whether the kernel category is enabled.
     *
     * @return {@code true} when the platform should drive the four pieces
     */
    public static boolean enabled() {
        try {
            return PrtsSwitches.enabled(CATEGORY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return {@code true} when an unregistered write is refused instead of queued as an intent */
    public static boolean enforceUnregisteredWrites() {
        return feature(ENFORCE_UNREGISTERED_WRITES);
    }

    /** @return {@code true} when the per-class self timers record samples */
    public static boolean selfTimers() {
        return feature(SELF_TIMERS);
    }

    /** @return {@code true} when the per-tick share table is planned */
    public static boolean shareTable() {
        return feature(SHARE_TABLE);
    }

    /** @return {@code true} when wait observations are collected */
    public static boolean waitRegistry() {
        return feature(WAIT_REGISTRY);
    }

    /** @return {@code true} when the real world write paths are watched */
    public static boolean writePathGuard() {
        return feature(WRITE_PATH_GUARD);
    }

    /** @return {@code true} when the commit segment walks the intent channel */
    public static boolean commitIntents() {
        return feature(COMMIT_INTENTS);
    }

    /** @return {@code true} when an undeclared write is handed to the intent channel */
    public static boolean routeUnregisteredWrites() {
        return feature(ROUTE_UNREGISTERED_WRITES);
    }

    /** @return window length in ticks, never shorter than ten minutes */
    public static long selfWindowTicks() {
        return 20L * number(SELF_WINDOW_SECONDS);
    }

    /** @return warm-up length in ticks */
    public static long selfWarmupTicks() {
        return 20L * number(SELF_WARMUP_SECONDS);
    }

    /** @return time one tick may spend in total, in milliseconds */
    public static double eBudgetMs() {
        return number(E_BUDGET_MS);
    }

    /** @return fair share of one world per tick, in milliseconds */
    public static double worldShareMs() {
        return number(WORLD_SHARE_MS);
    }

    /** @return the reserved pool in milliseconds */
    public static double reserveMs() {
        return number(RESERVE_MS);
    }

    /** @return the part of the budget the class rows may not borrow */
    public static double hostOverheadMs() {
        return number(HOST_OVERHEAD_MS);
    }

    /** @return the intent channel depth at which one world's shard refuses */
    public static int intentQueueCap() {
        return clamp(number(INTENT_QUEUE_CAP), 1, INTENT_QUEUE_CAP_MAX);
    }

    /** @return how many intents one tick's commit walk may reach */
    public static int commitBudget() {
        return clamp(number(COMMIT_BUDGET), 1, COMMIT_BUDGET_MAX);
    }

    /**
     * Clamps one whole-number setting into its accepted range.
     *
     * <p>The configuration layer already clamps what it reads against the declared range; this is the
     * second bound, at the point a piece of the kernel turns the setting into work. A depth limit and
     * a per-tick budget are what keep one tick from doing an unbounded amount of synchronous work, so
     * neither of them is ever used unclamped.</p>
     *
     * @param value the value read from the configuration layer
     * @param low   lower bound, applied first
     * @param high  upper bound
     * @return the value inside the range
     */
    public static int clamp(int value, int low, int high) {
        return Math.min(high, Math.max(low, value));
    }

    /** @return the upper bound of one wait, in milliseconds */
    public static int waitBoundMs() {
        return number(WAIT_BOUND_MS);
    }

    /** @return the retry budget one attempt carries */
    public static int retryBudget() {
        return number(RETRY_BUDGET);
    }

    /** @return whether the parallel dispatch of the first domain is on */
    public static boolean dispatchParallel() {
        return feature(DISPATCH_PARALLEL);
    }

    /** @return the declared worker count, zero meaning derive it from the machine */
    public static int workerCountDeclared() {
        return number(WORKER_COUNT);
    }

    /** @return how many batches may wait in the pool before the tick thread takes over */
    public static int workerQueueCap() {
        return clamp(number(WORKER_QUEUE_CAP), 1, WORKER_QUEUE_CAP_MAX);
    }

    /** @return how many chunks one region covers on a side */
    public static int workerBatchChunks() {
        return clamp(number(WORKER_BATCH_CHUNKS), 1, WORKER_BATCH_CHUNKS_MAX);
    }

    /** @return how long the merge waits past the tick boundary before it cancels */
    public static int workerDeadlineGraceMs() {
        return clamp(number(WORKER_DEADLINE_GRACE_MS), 0, WORKER_DEADLINE_GRACE_MS_MAX);
    }

    /** @return how many retryable faults a worker attempt may carry */
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