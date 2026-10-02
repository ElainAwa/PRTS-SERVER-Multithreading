/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * The controlled channel a write takes when it may not write directly.
 *
 * <p>The channel is sharded by the destination world the intent carries: one queue and one frozen
 * order per world, so a write that cannot land in one world cannot hold the writes of another, and a
 * world that unloads takes its own backlog with it instead of stopping the whole channel. An intent
 * without a world lands in the one shard that carries the empty world key.</p>
 *
 * <p>An intent is frozen into its shard with the order the channel gives it at the moment it is
 * accepted, and the commit segment walks exactly that order. Reaching an intent is what applies it:
 * the segment asks the payload bound to the queue, and the write lands on the thread that drives the
 * tick - the kernel never writes world state from anywhere else.</p>
 *
 * <p>The frozen order is assigned here, under the same lock that accepts the intent, so the orders
 * inside a shard are contiguous: an attempt refused at the depth limit never consumes one, and a
 * cursor that walks the shard one step at a time can never meet a gap.</p>
 *
 * <p>A refusal is bounded. The payload says whether its refusal is worth another attempt and the
 * channel offers the same head again at most until the retry budget is spent; past that budget, and
 * for a refusal the payload marks final, the head is released with its code and its diagnostic five,
 * its payload is told to forget the write, and the shard moves on. That is what keeps one
 * unlandable write from blocking the writes behind it for the rest of the process.</p>
 *
 * <p>The queue has an explicit depth per shard. At the depth it refuses instead of growing, and the
 * refusal is counted with its diagnostic five, so pressure becomes visible instead of silent.</p>
 */
public final class IntentQueue {

    /** World key of an intent that names no world. */
    public static final String NO_WORLD = "";

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

    /** One world's queue: its waiting intents, the order it reached and the head's attempt count. */
    private static final class Shard {

        private final Deque<WriteIntent> waiting = new ArrayDeque<>();
        private long frozenOrder;
        private int headAttempts;
    }

    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final IntSupplier capacity;
    private final IntSupplier retryBudget;
    private final AtomicLong nextIntentId = new AtomicLong(1L);
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong orderViolations = new AtomicLong();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong payloadRefusals = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong retryExhausted = new AtomicLong();
    private final AtomicLong lastExecTick = new AtomicLong(-1L);
    private volatile IntentPayload payloadExecutor;
    private int lastDepth;

    /**
     * Creates a queue.
     *
     * @param capacity    current depth limit of one world shard, read at every enqueue so a
     *                    configuration reload applies without a restart
     * @param retryBudget attempts one failing head may consume before its refusal is final, read at
     *                    every refusal so a reload applies without a restart
     */
    public IntentQueue(IntSupplier capacity, IntSupplier retryBudget) {
        this.capacity = capacity;
        this.retryBudget = retryBudget;
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
     * Freezes one draft into the shard of the world it targets.
     *
     * @param draft the intent to freeze; the order it carries is replaced by the one the channel
     *              assigns here
     * @return the handle, or a refusal when the shard is at its depth
     */
    public synchronized EnqueueResult enqueue(WriteIntent draft) {
        Shard shard = shard(worldKeyOf(draft), true);
        int limit = Math.max(1, capacity.getAsInt());
        if (shard.waiting.size() >= limit) {
            long wouldBe = shard.frozenOrder;
            rejectedFull.incrementAndGet();
            return EnqueueResult.refused(RejectCode.QUEUE_CAP_EXCEEDED,
                new Diag5(RejectCode.QUEUE_CAP_EXCEEDED.text(), draft.siteId(), "",
                    draft.srcWorldId(), wouldBe));
        }
        WriteIntent intent = draft.withFrozenOrder(shard.frozenOrder++);
        shard.waiting.addLast(intent);
        enqueued.incrementAndGet();
        return EnqueueResult.accepted(new IntentHandle(intent.intentId(), intent.frozenOrder()));
    }

    /**
     * Commits the head of one world's shard, provided it carries the expected order.
     *
     * <p>The head is handed to the bound payload before it is removed; a refusal that is worth
     * another attempt leaves the shard untouched, so the next attempt sees the same head and the
     * frozen order cannot be repaired by a later commit. A refusal that is final, and a refusal past
     * the retry budget, release the head instead: the position is consumed with its code, the payload
     * is told to forget the write, and the shard walks on.</p>
     *
     * @param worldKey     world whose shard is walked
     * @param planOrderSeq order the commit segment expects next in that shard
     * @param tickIndex    tick the commit belongs to
     * @return the commit sequence number, or a refusal
     */
    public synchronized CommitOrder commit(String worldKey, long planOrderSeq, long tickIndex) {
        Shard shard = shard(worldKey, false);
        if (shard == null || shard.waiting.isEmpty()) {
            return CommitOrder.committed(committed.get());
        }
        WriteIntent head = shard.waiting.peekFirst();
        if (head.frozenOrder() != planOrderSeq) {
            orderViolations.incrementAndGet();
            return CommitOrder.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                diag(head, RejectCode.COMMIT_ORDER_VIOLATION, planOrderSeq));
        }
        IntentPayload executor = payloadExecutor;
        IntentPayload.Outcome outcome = executor == null
            ? IntentPayload.Outcome.rejected(RejectCode.NATIVE_UNDECLARED)
            : executor.apply(head);
        if (!outcome.applied()) {
            payloadRefusals.incrementAndGet();
            shard.headAttempts++;
            if (outcome.finalRefusal() || shard.headAttempts > Math.max(0, retryBudget.getAsInt())) {
                if (!outcome.finalRefusal()) {
                    retryExhausted.incrementAndGet();
                }
                releaseHead(shard, executor);
                return CommitOrder.released(outcome.code(),
                    diag(head, outcome.code(), planOrderSeq));
            }
            return CommitOrder.refused(outcome.code(), diag(head, outcome.code(), planOrderSeq));
        }
        shard.headAttempts = 0;
        executed.incrementAndGet();
        lastExecTick.set(tickIndex);
        shard.waiting.removeFirst();
        return CommitOrder.committed(committed.incrementAndGet());
    }

    /** @return the next intent identity, also used by the caller for its own record */
    public long nextIntentId() {
        return nextIntentId.getAndIncrement();
    }

    /** @return current queue depth over every world */
    public synchronized int depth() {
        int total = 0;
        for (Shard shard : shards.values()) {
            total += shard.waiting.size();
        }
        return total;
    }

    /**
     * Returns the depth of one world's shard.
     *
     * @param worldKey world to read
     * @return the number of intents waiting in that shard
     */
    public synchronized int depth(String worldKey) {
        Shard shard = shard(worldKey, false);
        return shard == null ? 0 : shard.waiting.size();
    }

    /** @return the worlds that carry a shard, in the order they were first written to */
    public synchronized List<String> worlds() {
        return new ArrayList<>(shards.keySet());
    }

    /** @return the number of world shards */
    public synchronized int shardCount() {
        return shards.size();
    }

    /** @return change of the total queue depth since the previous call, the progress signal */
    public synchronized int depthSlope() {
        int slope = depth() - lastDepth;
        lastDepth = depth();
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

    /** @return enqueues refused because a shard was at its depth */
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

    /** @return intents released without being applied, final refusals included */
    public long releasedCount() {
        return released.get();
    }

    /** @return intents released because their retry budget was spent */
    public long retryExhaustedCount() {
        return retryExhausted.get();
    }

    /**
     * Returns the tick the last write was applied on.
     *
     * @return the tick of the last applied intent, or {@code -1} when none was applied yet
     */
    public long lastExecTick() {
        return lastExecTick.get();
    }

    /** @return the depth limit currently in effect for one shard */
    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

    /** @return the retry budget currently in effect */
    public int retryBudget() {
        return Math.max(0, retryBudget.getAsInt());
    }

    private void releaseHead(Shard shard, IntentPayload executor) {
        WriteIntent head = shard.waiting.removeFirst();
        shard.headAttempts = 0;
        released.incrementAndGet();
        if (executor != null) {
            executor.release(head);
        }
    }

    private Shard shard(String worldKey, boolean create) {
        String key = worldKey == null ? NO_WORLD : worldKey;
        Shard shard = shards.get(key);
        if (shard == null && create) {
            shard = new Shard();
            shards.put(key, shard);
        }
        return shard;
    }

    private static String worldKeyOf(WriteIntent draft) {
        return draft.dstWorldId() == null ? NO_WORLD : draft.dstWorldId();
    }

    private static Diag5 diag(WriteIntent intent, RejectCode code, long planOrderSeq) {
        return new Diag5(code.text(), intent.siteId(), "", intent.srcWorldId(), planOrderSeq);
    }
}
