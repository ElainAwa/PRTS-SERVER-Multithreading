/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * The single controlled channel a write takes when it may not write directly.
 *
 * <p>This batch lands the shape, not the world write: an intent is frozen into the queue with the
 * order it had at planning time, and the commit segment only walks that order. Nothing here touches
 * world state, which is what makes the channel safe to land before the commit segment exists.</p>
 *
 * <p>The queue has an explicit depth. At the depth it refuses instead of growing, and the refusal is
 * counted with its diagnostic five, so pressure becomes visible instead of silent.</p>
 */
public final class IntentQueue {

    /** Handle of an accepted intent. */
    public record IntentHandle(long intentId, long frozenOrder) {
    }

    /** Result of an enqueue: accepted with a handle, or refused with a code and diagnostics. */
    public record EnqueueResult(boolean accepted, IntentHandle handle, RejectCode code, Diag5 diag) {

        /** @return an accepted result */
        public static EnqueueResult accepted(IntentHandle handle) {
            return new EnqueueResult(true, handle, null, null);
        }

        /** @return a refused result */
        public static EnqueueResult refused(RejectCode code, Diag5 diag) {
            return new EnqueueResult(false, null, code, diag);
        }
    }

    private final Deque<WriteIntent> queue = new ArrayDeque<>();
    private final IntSupplier capacity;
    private final AtomicLong nextIntentId = new AtomicLong(1L);
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong orderViolations = new AtomicLong();
    private volatile int lastDepth;

    /**
     * Creates a queue.
     *
     * @param capacity current depth limit, read at every enqueue so a configuration reload applies
     *                 without a restart
     */
    public IntentQueue(IntSupplier capacity) {
        this.capacity = capacity;
    }

    /**
     * Freezes one intent into the queue.
     *
     * @param intent the intent, with the order it had at planning time
     * @return the handle, or a refusal when the queue is at its depth
     */
    public synchronized EnqueueResult enqueue(WriteIntent intent) {
        int limit = Math.max(1, capacity.getAsInt());
        if (queue.size() >= limit) {
            rejectedFull.incrementAndGet();
            return EnqueueResult.refused(RejectCode.QUEUE_CAP_EXCEEDED,
                new Diag5(RejectCode.QUEUE_CAP_EXCEEDED.text(), intent.siteId(), "",
                    intent.srcWorldId(), intent.frozenOrder()));
        }
        queue.addLast(intent);
        enqueued.incrementAndGet();
        return EnqueueResult.accepted(new IntentHandle(intent.intentId(), intent.frozenOrder()));
    }

    /**
     * Commits the head of the queue, provided it carries the expected order.
     *
     * @param planOrderSeq order the commit segment expects next
     * @return the commit sequence number, or a refusal that leaves the queue untouched
     */
    public synchronized CommitOrder commit(long planOrderSeq) {
        WriteIntent head = queue.peekFirst();
        if (head == null) {
            return CommitOrder.committed(committed.get());
        }
        if (head.frozenOrder() != planOrderSeq) {
            orderViolations.incrementAndGet();
            return CommitOrder.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                new Diag5(RejectCode.COMMIT_ORDER_VIOLATION.text(), head.siteId(), "",
                    head.srcWorldId(), planOrderSeq));
        }
        queue.removeFirst();
        return CommitOrder.committed(committed.incrementAndGet());
    }

    /** @return the next intent identity, also used by the caller for its own record */
    public long nextIntentId() {
        return nextIntentId.getAndIncrement();
    }

    /** @return current queue depth */
    public synchronized int depth() {
        return queue.size();
    }

    /** @return change of the queue depth since the previous call, the progress signal of the queue */
    public synchronized int depthSlope() {
        int slope = queue.size() - lastDepth;
        lastDepth = queue.size();
        return slope;
    }

    /** @return intents accepted since the process started */
    public long enqueuedCount() {
        return enqueued.get();
    }

    /** @return intents committed since the process started */
    public long committedCount() {
        return committed.get();
    }

    /** @return enqueues refused because the queue was at its depth */
    public long rejectedFullCount() {
        return rejectedFull.get();
    }

    /** @return commits refused because the order did not match the frozen one */
    public long orderViolationCount() {
        return orderViolations.get();
    }

    /** @return the depth limit currently in effect */
    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }
}
