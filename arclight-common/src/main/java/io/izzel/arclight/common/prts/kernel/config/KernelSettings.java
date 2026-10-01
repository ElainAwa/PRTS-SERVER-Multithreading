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

    /** Depth at which the intent channel refuses instead of queueing. */
    public static final String INTENT_QUEUE_CAP = "intent-queue-cap";

    /** Upper bound of one wait, in milliseconds. */
    public static final String WAIT_BOUND_MS = "wait-bound-ms";

    /** Number of retries one attempt carries before a refusal is final. */
    public static final String RETRY_BUDGET = "retry-budget";

    private KernelSettings() {
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

    /** @return the intent channel depth at which it refuses */
    public static int intentQueueCap() {
        return number(INTENT_QUEUE_CAP);
    }

    /** @return the upper bound of one wait, in milliseconds */
    public static int waitBoundMs() {
        return number(WAIT_BOUND_MS);
    }

    /** @return the retry budget one attempt carries */
    public static int retryBudget() {
        return number(RETRY_BUDGET);
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
