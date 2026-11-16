/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** What one bounded drain of one world did. The caller advances its cursor by
     * {@code consumedOrders}; a drain that leaves a head behind is not complete, and a refused or
     * faulted head is not counted as consumed. */
    public record DrainResult(int calls, int items, long nanos, int applied, int cancelled,
                              int faultUnknown, int remaining, int consumedOrders,
                              boolean complete) {
    }

    private static final class Shard {

        private final Deque<WriteIntent> waiting = new ArrayDeque<>();
        private long frozenOrder;
        private int headAttempts;
        private boolean faulted;
        private RejectCode faultCode;
    }

    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final Set<String> closed = new HashSet<>();
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
    private final AtomicLong faultUnknown = new AtomicLong();
    private final AtomicLong closedRefusals = new AtomicLong();
    private final AtomicLong releaseRefusals = new AtomicLong();
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

    /** Freezes one draft into the shard of the world it targets. A world whose lifecycle was
     * closed refuses the intent whole, so no payload is bound for a world that is going away. */
    public synchronized EnqueueResult enqueue(WriteIntent draft) {
        String key = worldKeyOf(draft);
        Shard shard = shard(key, false);
        if (closed.contains(key)) {
            long wouldBe = shard == null ? 0L : shard.frozenOrder;
            closedRefusals.incrementAndGet();
            return EnqueueResult.refused(RejectCode.WORLD_LIFECYCLE_DENIED,
                new Diag5(RejectCode.WORLD_LIFECYCLE_DENIED.text(), draft.siteId(), "",
                    draft.srcWorldId(), wouldBe));
        }
        shard = shard(key, true);
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
     * frozen order cannot be repaired by a later commit. A refusal whose effect is unknown is
     * final and keeps the head and its payload handle: nothing may silently drop an effect that
     * may already have landed. */
    public synchronized CommitOrder commit(String worldKey, long planOrderSeq, long tickIndex) {
        String key = worldKey(worldKey);
        Shard shard = shards.get(key);
        if (shard == null || shard.waiting.isEmpty()) {
            return CommitOrder.committed(committed.get());
        }
        WriteIntent head = shard.waiting.peekFirst();
        if (head.frozenOrder() != planOrderSeq) {
            orderViolations.incrementAndGet();
            return CommitOrder.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                diag(head, RejectCode.COMMIT_ORDER_VIOLATION, planOrderSeq));
        }
        if (shard.faulted) {
            RejectCode fault = shard.faultCode == null ? RejectCode.VERSION_MISMATCH : shard.faultCode;
            return CommitOrder.faulted(fault, diag(head, fault, planOrderSeq));
        }
        IntentPayload executor = payloadExecutor;
        IntentPayload.Outcome outcome = executor == null
            ? IntentPayload.Outcome.rejected(RejectCode.NATIVE_UNDECLARED)
            : executor.apply(head);
        if (!outcome.applied()) {
            payloadRefusals.incrementAndGet();
            shard.headAttempts++;
            if (outcome.effectUnknown()) {
                shard.faulted = true;
                shard.faultCode = outcome.code();
                faultUnknown.incrementAndGet();
                return CommitOrder.faulted(outcome.code(), diag(head, outcome.code(), planOrderSeq));
            }
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

    /** Drains one world's shard within an item budget and an absolute {@link System#nanoTime()}
     * deadline; a zero deadline means no time bound. Only the head is attempted, so the frozen
     * order holds across ticks. A retryable refusal, a faulted head or an exhausted budget stops
     * the drain and says so; an effect-unknown fault is counted and never re-dispatched. */
    public synchronized DrainResult drainWorld(String worldKey, int itemBudget, long deadlineNanos,
                                               long tickIndex) {
        long startedAt = System.nanoTime();
        String key = worldKey(worldKey);
        Shard shard = shards.get(key);
        if (shard == null || shard.waiting.isEmpty()) {
            return new DrainResult(0, 0, System.nanoTime() - startedAt, 0, 0, 0, 0, 0, true);
        }
        if (shard.faulted) {
            return new DrainResult(0, 0, System.nanoTime() - startedAt, 0, 0, 1,
                shard.waiting.size(), 0, false);
        }
        int budget = Math.max(1, itemBudget);
        int calls = 0;
        int items = 0;
        int applied = 0;
        int cancelled = 0;
        int faults = 0;
        int consumed = 0;
        while (!shard.waiting.isEmpty() && items < budget) {
            if (deadlineNanos > 0L && System.nanoTime() >= deadlineNanos) {
                break;
            }
            WriteIntent head = shard.waiting.peekFirst();
            calls++;
            CommitOrder order = commit(key, head.frozenOrder(), tickIndex);
            if (order.committed()) {
                applied++;
                items++;
                consumed++;
                continue;
            }
            if (order.released()) {
                cancelled++;
                items++;
                consumed++;
                continue;
            }
            if (order.faulted()) {
                faults++;
            }
            break;
        }
        return new DrainResult(calls, items, System.nanoTime() - startedAt, applied, cancelled,
            faults, shard.waiting.size(), consumed, shard.waiting.isEmpty());
    }

    /** Hands the unattempted intents of one world to the payload's release and drops its shard.
     * A shard whose head was attempted or faulted is kept whole: its payload handle may hold an
     * effect and releasing it here would be the silent release the drain gate exists to prevent. */
    public synchronized int releaseWorld(String worldKey) {
        String key = worldKey(worldKey);
        Shard shard = shards.get(key);
        if (shard == null) {
            return 0;
        }
        if (shard.faulted || shard.headAttempts > 0) {
            releaseRefusals.incrementAndGet();
            return 0;
        }
        shards.remove(key);
        if (shard.waiting.isEmpty()) {
            return 0;
        }
        IntentPayload executor = payloadExecutor;
        int leaving = shard.waiting.size();
        for (WriteIntent intent : shard.waiting) {
            if (executor != null) {
                executor.release(intent);
            }
        }
        shard.waiting.clear();
        released.addAndGet(leaving);
        return leaving;
    }

    /** Stops one world from accepting intents; the tombstone survives the shard's release and is
     * cleared only when that world is created again. */
    public synchronized boolean closeWorld(String worldKey) {
        return closed.add(worldKey(worldKey));
    }

    /** Clears the tombstone of a world that was created again. */
    public synchronized boolean openWorld(String worldKey) {
        return closed.remove(worldKey(worldKey));
    }

    public synchronized boolean worldClosed(String worldKey) {
        return closed.contains(worldKey(worldKey));
    }

    public synchronized int closedWorldCount() {
        return closed.size();
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

    public long faultUnknownCount() {
        return faultUnknown.get();
    }

    public long closedRefusalCount() {
        return closedRefusals.get();
    }

    public long releaseRefusalCount() {
        return releaseRefusals.get();
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
        String key = worldKey(worldKey);
        Shard shard = shards.get(key);
        if (shard == null && create) {
            shard = new Shard();
            shards.put(key, shard);
        }
        return shard;
    }

    private static String worldKey(String worldKey) {
        return worldKey == null ? NO_WORLD : worldKey;
    }

    private static String worldKeyOf(WriteIntent draft) {
        return draft.dstWorldId() == null ? NO_WORLD : draft.dstWorldId();
    }

    private static Diag5 diag(WriteIntent intent, RejectCode code, long planOrderSeq) {
        return new Diag5(code.text(), intent.siteId(), "", intent.srcWorldId(), planOrderSeq);
    }
}
