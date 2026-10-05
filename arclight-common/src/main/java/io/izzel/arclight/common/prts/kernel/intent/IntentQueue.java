/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/** The channel is sharded by the destination world the intent carries: one queue and one frozen
 * order per world, so a write that cannot land in one world cannot hold the writes of another, and
 * a world that unloads takes its own backlog with it instead of stopping the whole channel. */
public final class IntentQueue {

    /** World key of an intent that names no world. */
    public static final String NO_WORLD = "";

    /** Handle of an accepted intent. */
    public record IntentHandle(long intentId, long frozenOrder) {
    }

    /** Result of an enqueue: accepted with a handle, or refused with a code and diagnostics. */
    public record EnqueueResult(boolean accepted, IntentHandle handle, RejectCode code, Diag5 diag) {

        public static EnqueueResult accepted(IntentHandle handle) {
            return new EnqueueResult(true, handle, null, null);
        }

        public static EnqueueResult refused(RejectCode code, Diag5 diag) {
            return new EnqueueResult(false, null, code, diag);
        }
    }

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

    /** Creates a queue. */
    public IntentQueue(IntSupplier capacity, IntSupplier retryBudget) {
        this.capacity = capacity;
        this.retryBudget = retryBudget;
    }

    /** Binds the segment that applies what the commit reaches. */
    public void bindPayload(IntentPayload payloadExecutor) {
        this.payloadExecutor = payloadExecutor;
    }

    /** Freezes one draft into the shard of the world it targets. */
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

    /** The head is handed to the bound payload before it is removed; a refusal that is worth
     * another attempt leaves the shard untouched, so the next attempt sees the same head and the
     * frozen order cannot be repaired by a later commit. */
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

    public long nextIntentId() {
        return nextIntentId.getAndIncrement();
    }

    /** The order the head of one world's shard was frozen with, or -1 when the shard is empty. It is
     * read by the commit log so a logged intent carries the order the channel gave it. */
    public synchronized long headOrder(String worldKey) {
        Shard shard = shard(worldKey, false);
        if (shard == null || shard.waiting.isEmpty()) {
            return -1L;
        }
        return shard.waiting.peekFirst().frozenOrder();
    }

    public synchronized int depth() {
        int total = 0;
        for (Shard shard : shards.values()) {
            total += shard.waiting.size();
        }
        return total;
    }

    /** Returns the depth of one world's shard. */
    public synchronized int depth(String worldKey) {
        Shard shard = shard(worldKey, false);
        return shard == null ? 0 : shard.waiting.size();
    }

    public synchronized List<String> worlds() {
        return new ArrayList<>(shards.keySet());
    }

    public synchronized int shardCount() {
        return shards.size();
    }

    public synchronized int depthSlope() {
        int slope = depth() - lastDepth;
        lastDepth = depth();
        return slope;
    }

    public long enqueuedCount() {
        return enqueued.get();
    }

    public long committedCount() {
        return committed.get();
    }

    public long rejectedFullCount() {
        return rejectedFull.get();
    }

    public long orderViolationCount() {
        return orderViolations.get();
    }

    public long executedCount() {
        return executed.get();
    }

    public long payloadRefusalCount() {
        return payloadRefusals.get();
    }

    public long releasedCount() {
        return released.get();
    }

    public long retryExhaustedCount() {
        return retryExhausted.get();
    }

    /** Returns the tick the last write was applied on. */
    public long lastExecTick() {
        return lastExecTick.get();
    }

    public int capacity() {
        return Math.max(1, capacity.getAsInt());
    }

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
