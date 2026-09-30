/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The single controlled channel a write takes when it may not write directly.
 *
 * <p>An intent is frozen into the queue with the order it had at planning time, and the commit
 * segment walks exactly that order. Reaching an intent is what applies it: the segment asks the
 * payload bound to the queue, and the write lands on the thread that drives the tick - the kernel
 * never writes world state from anywhere else.</p>
 *
 * <p>Applying what the segment reaches is a switch. While it is off the segment only proves the
 * order and drops the intent, which is the shape this channel landed with; the readout says which
 * of the two modes ran, so a dropped intent is never a quiet one. While it is on, an intent whose
 * payload is missing and an intent whose write reports that it did not land both refuse the commit
 * with a code - the head stays where it is and the order stays intact.</p>
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
    private final BooleanSupplier executePayloads;
    private final AtomicLong nextIntentId = new AtomicLong(1L);
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong orderViolations = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong payloadRefusals = new AtomicLong();
    private final AtomicLong shapeOnly = new AtomicLong();
    private volatile IntentPayload payloadExecutor;
    private volatile int lastDepth;

    /**
     * Creates a queue that proves the frozen order without applying what it reaches.
     *
     * @param capacity current depth limit, read at every enqueue so a configuration reload applies
     *                 without a restart
     */
    public IntentQueue(IntSupplier capacity) {
        this(capacity, () -> false);
    }

    /**
     * Creates a queue.
     *
     * @param capacity        current depth limit, read at every enqueue so a configuration reload
     *                        applies without a restart
     * @param executePayloads read at every commit, so a configuration reload applies without a
     *                        restart
     */
    public IntentQueue(IntSupplier capacity, BooleanSupplier executePayloads) {
        this.capacity = capacity;
        this.executePayloads = executePayloads;
    }

    /**
     * Binds the segment that applies what the commit reaches.
     *
     * @param payloadExecutor the executor, or {@code null} when none is installed
     */
    public void bindPayload(IntentPayload payloadExecutor) {
        this.payloadExecutor = payloadExecutor;
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
     * <p>With the applying switch on, the head is handed to the bound payload before it is removed;
     * a refusal leaves the queue untouched, so the next attempt sees the same head and the frozen
     * order cannot be repaired by a later commit.</p>
     *
     * @param planOrderSeq order the commit segment expects next
     * @return the commit sequence number, or a refusal
     */
    public synchronized CommitOrder commit(long planOrderSeq) {
        WriteIntent head = queue.peekFirst();
        if (head == null) {
            return CommitOrder.committed(committed.get());
        }
        if (head.frozenOrder() != planOrderSeq) {
            orderViolations.incrementAndGet();
            return CommitOrder.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                diag(head, RejectCode.COMMIT_ORDER_VIOLATION, planOrderSeq));
        }
        if (executePayloads.getAsBoolean()) {
            IntentPayload executor = payloadExecutor;
            RejectCode refusal = executor == null ? RejectCode.NATIVE_UNDECLARED : executor.apply(head);
            if (refusal != null) {
                payloadRefusals.incrementAndGet();
                return CommitOrder.refused(refusal, diag(head, refusal, planOrderSeq));
            }
            executed.incrementAndGet();
        } else {
            shapeOnly.incrementAndGet();
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

    /** @return intents whose write was applied at the commit */
    public long executedCount() {
        return executed.get();
    }

    /** @return commits refused because the write did not land */
    public long payloadRefusalCount() {
        return payloadRefusals.get();
    }

    /** @return intents whose order was proven and which were dropped without being applied */
    public long shapeOnlyCount() {
        return shapeOnly.get();
    }

    /** @return the mode the next commit runs in */
    public String commitMode() {
        return executePayloads.getAsBoolean() ? "execute" : "shape";
    }

    /** @return the depth limit currently in effect */
    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

    private static Diag5 diag(WriteIntent intent, RejectCode code, long planOrderSeq) {
        return new Diag5(code.text(), intent.siteId(), "", intent.srcWorldId(), planOrderSeq);
    }
}
