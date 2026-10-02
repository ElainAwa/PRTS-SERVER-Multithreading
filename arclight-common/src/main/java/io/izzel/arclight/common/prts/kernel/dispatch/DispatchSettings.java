/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

/**
 * The resolved policy of one dispatch run: how many workers, how deep the queue may grow, how many
 * chunks one region covers, how much grace a deadline leaves and how often a batch may be retried.
 *
 * <p>The declared names and defaults live in the configuration layer; this class turns them into
 * one bounded policy at the moment a tick needs it, so a value is never used unclamped. The worker
 * count is either declared or derived from the available processors, and the derived form is capped
 * so a large machine does not turn one tick into a wide fan-out.</p>
 */
public final class DispatchSettings {

    /** Upper bound of the derived worker count. */
    public static final int DERIVED_WORKER_CAP = 4;

    /** Upper bound an operator may declare for the worker count. */
    public static final int DECLARED_WORKER_CAP = 8;

    /** Upper bound of the queue depth. */
    public static final int QUEUE_CAP_MAX = 256;

    /** Upper bound of the region size in chunks. */
    public static final int BATCH_CHUNKS_MAX = 64;

    /** Upper bound of the deadline grace in milliseconds. */
    public static final int DEADLINE_GRACE_MS_MAX = 1000;

    /** Upper bound of the worker retry budget. */
    public static final int RETRY_BUDGET_MAX = 2;

    private DispatchSettings() {
    }

    /**
     * The bounded policy one tick runs with.
     *
     * @param workerCount     how many worker threads the pool holds
     * @param queueCap        how many batches may be in flight at once
     * @param batchChunks     how many chunks one region covers on a side
     * @param deadlineGraceMs how long the merge waits past the tick boundary
     * @param retryBudget     how many retryable faults a batch may carry
     */
    public record Policy(int workerCount, int queueCap, int batchChunks, int deadlineGraceMs,
                         int retryBudget) {
    }

    /**
     * Derives the worker count of a machine.
     *
     * @param cores the available processors
     * @return one to {@link #DERIVED_WORKER_CAP} workers, never more than the machine can run
     */
    public static int derivedWorkerCount(int cores) {
        return Math.max(1, Math.min(DERIVED_WORKER_CAP, cores - 1));
    }

    /**
     * Resolves the declared worker count, falling back to the derived form.
     *
     * @param declared the declared setting, zero meaning derive
     * @param cores    the available processors
     * @return the worker count inside its two bounds
     */
    public static int workerCount(int declared, int cores) {
        if (declared <= 0) {
            return derivedWorkerCount(cores);
        }
        return Math.min(DECLARED_WORKER_CAP, Math.max(1, declared));
    }

    /**
     * Resolves the policy of this process.
     *
     * @return the bounded policy
     */
    public static Policy resolve() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int workers = workerCount(KernelSettings.workerCountDeclared(), cores);
        int queueCap = KernelSettings.clamp(KernelSettings.workerQueueCap(), 1, QUEUE_CAP_MAX);
        int chunks = KernelSettings.clamp(KernelSettings.workerBatchChunks(), 1, BATCH_CHUNKS_MAX);
        int grace = KernelSettings.clamp(KernelSettings.workerDeadlineGraceMs(), 0,
            DEADLINE_GRACE_MS_MAX);
        // The worker budget may never exceed the retry budget the intent channel already uses.
        int retry = Math.min(KernelSettings.clamp(KernelSettings.workerRetryBudget(), 0,
            RETRY_BUDGET_MAX), KernelSettings.retryBudget());
        return new Policy(workers, queueCap, chunks, grace, retry);
    }
}
