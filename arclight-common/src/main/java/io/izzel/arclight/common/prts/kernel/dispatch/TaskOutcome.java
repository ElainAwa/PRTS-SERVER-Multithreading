/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.SlotRef;

/** The terminal result of one dispatched batch. Every outcome carries the thread it ran on, so a
 * reader can tell a worker result from one the tick thread had to redo. */
public record TaskOutcome(long batchId, Status status, int attempts, long execThreadId,
                          String execThreadName, SlotRef outSlotRef, long resultHash, String code) {

    /** The five terminal statuses of one dispatched batch. */
    public enum Status {

        EXECUTED("executed"),
        RETRIED("retried"),
        FELLBACK("fellback"),
        CANCELLED("cancelled"),
        FAILED("failed");

        private final String key;

        Status(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public static TaskOutcome executed(long batchId, int attempts, Thread thread, SlotRef slot,
                                       long resultHash) {
        return new TaskOutcome(batchId, Status.EXECUTED, attempts, thread.threadId(),
            thread.getName(), slot, resultHash, null);
    }

    public static TaskOutcome retried(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.RETRIED, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }

    public static TaskOutcome fellback(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.FELLBACK, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }

    public static TaskOutcome cancelled(long batchId, int attempts, long threadId,
                                        String threadName, String code) {
        return new TaskOutcome(batchId, Status.CANCELLED, attempts, threadId, threadName, null, 0L,
            code);
    }

    public static TaskOutcome failed(long batchId, int attempts, Thread thread, String code) {
        return new TaskOutcome(batchId, Status.FAILED, attempts, thread.threadId(),
            thread.getName(), null, 0L, code);
    }
}
