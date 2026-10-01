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
 * <p>An intent is frozen into the queue with the order the channel gives it at the moment it is
 * accepted, and the commit segment walks exactly that order. Reaching an intent is what applies it:
 * the segment asks the payload bound to the queue, and the write lands on the thread that drives
 * the tick - the kernel never writes world state from anywhere else.</p>
 *
 * <p>The frozen order is assigned here, under the same lock that accepts the intent, so the orders
 * in the queue are contiguous: an attempt refused at the depth limit never consumes one, and a
 * cursor that walks the queue one step at a time can never meet a gap.</p>
 *
 * <p>The queue does not decide when it is walked. Whoever walks it leaves what it does not reach in
 * place, so an intent is never dropped quietly: it is either applied, or refused with a code, or
 * still waiting - and the depth says how many are waiting.</p>
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
    private final AtomicLong nextFrozenOrder = new AtomicLong();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong orderViolations = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong payloadRefusals = new AtomicLong();
    private final AtomicLong lastExecTick = new AtomicLong(-1L);
    private volatile IntentPayload payloadExecutor;
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
     * Binds the segment that applies what the commit reaches.
     *
     * @param payloadExecutor the executor, or {@code null} when none is installed
     */
    public void bindPayload(IntentPayload payloadExecutor) {
        this.payloadExecutor = payloadExecutor;
    }

    /**
     * Freezes one draft into the queue.
     *
     * @param draft the intent to freeze; the order it carries is replaced by the one the channel
     *              assigns here
     * @return the handle, or a refusal when the queue is at its depth
     */
    public synchronized EnqueueResult enqueue(WriteIntent draft) {
        int limit = Math.max(1, capacity.getAsInt());
        if (queue.size() >= limit) {
            long wouldBe = nextFrozenOrder.get();
            rejectedFull.incrementAndGet();
            return EnqueueResult.refused(RejectCode.QUEUE_CAP_EXCEEDED,
                new Diag5(RejectCode.QUEUE_CAP_EXCEEDED.text(), draft.siteId(), "",
                    draft.srcWorldId(), wouldBe));
        }
        WriteIntent intent = draft.withFrozenOrder(nextFrozenOrder.getAndIncrement());
        queue.addLast(intent);
        enqueued.incrementAndGet();
        return EnqueueResult.accepted(new IntentHandle(intent.intentId(), intent.frozenOrder()));
    }

    /**
     * Commits the head of the queue, provided it carries the expected order.
     *
     * <p>The head is handed to the bound payload before it is removed; a refusal leaves the queue
     * untouched, so the next attempt sees the same head and the frozen order cannot be repaired by
     * a later commit.</p>
     *
     * @param planOrderSeq order the commit segment expects next
     * @param tickIndex    tick the commit belongs to
     * @return the commit sequence number, or a refusal
     */
    public synchronized CommitOrder commit(long planOrderSeq, long tickIndex) {
        WriteIntent head = queue.peekFirst();
        if (head == null) {
            return CommitOrder.committed(committed.get());
        }
        if (head.frozenOrder() != planOrderSeq) {
            orderViolations.incrementAndGet();
            return CommitOrder.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                diag(head, RejectCode.COMMIT_ORDER_VIOLATION, planOrderSeq));
        }
        IntentPayload executor = payloadExecutor;
        RejectCode refusal = executor == null ? RejectCode.NATIVE_UNDECLARED : executor.apply(head);
        if (refusal != null) {
            payloadRefusals.incrementAndGet();
            return CommitOrder.refused(refusal, diag(head, refusal, planOrderSeq));
        }
        executed.incrementAndGet();
        lastExecTick.set(tickIndex);
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

    /**
     * Returns the tick the last write was applied on.
     *
     * @return the tick of the last applied intent, or {@code -1} when none was applied yet
     */
    public long lastExecTick() {
        return lastExecTick.get();
    }

    /** @return the depth limit currently in effect */
    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

    private static Diag5 diag(WriteIntent intent, RejectCode code, long planOrderSeq) {
        return new Diag5(code.text(), intent.siteId(), "", intent.srcWorldId(), planOrderSeq);
    }
}
