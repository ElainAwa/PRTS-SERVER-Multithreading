/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.SlotRef;

/**
 * The terminal result of one dispatched batch.
 *
 * <p>Every outcome carries the thread it ran on, so a reader can tell a worker result from one the
 * tick thread had to redo. The status is terminal: a batch is dispatched once and answers exactly
 * once, and the five statuses are what the closure of a tick is expressed in.</p>
 */
public record TaskOutcome(long batchId, Status status, int attempts, long execThreadId,
                          String execThreadName, SlotRef outSlotRef, long resultHash, String code) {

    /** The five terminal statuses of one dispatched batch. */
    public enum Status {

        /** The worker finished the batch and filled its slot. */
        EXECUTED("executed"),
        /** The worker hit a retryable fault and left the batch to the tick thread. */
        RETRIED("retried"),
        /** The worker hit a non-retryable fault and left the batch to the tick thread. */
        FELLBACK("fellback"),
        /** The deadline passed before the batch produced a result; the tick thread redoes it. */
        CANCELLED("cancelled"),
        /** The worker died on a hard fault; the batch is redone on the tick thread. */
        FAILED("failed");

        private final String key;

        Status(String key) {
            this.key = key;
        }

        /** @return the stable name this status is published under */
        public String key() {
            return key;
        }
    }

    /** @return an outcome of a batch the worker finished */
    public static TaskOutcome executed(long batchId, int attempts, Thread thread, SlotRef slot,
                                       long resultHash) {
        return new TaskOutcome(batchId, Status.EXECUTED, attempts, thread.threadId(),
            thread.getName(), slot, resultHash, null);
    }

    /** @return an outcome of a batch a retryable fault ended early */
    public static TaskOutcome retried(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.RETRIED, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }

    /** @return an outcome of a batch a non-retryable fault ended early */
    public static TaskOutcome fellback(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.FELLBACK, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }

    /** @return an outcome of a batch the deadline cancelled */
    public static TaskOutcome cancelled(long batchId, int attempts, long threadId,
                                        String threadName, String code) {
        return new TaskOutcome(batchId, Status.CANCELLED, attempts, threadId, threadName, null, 0L,
            code);
    }

    /** @return an outcome of a batch whose worker died */
    public static TaskOutcome failed(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.FAILED, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }
}
