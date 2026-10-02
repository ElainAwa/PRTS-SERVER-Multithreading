/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-world submit contract of the intent channel.
 *
 * <p>Every world carries its own order and its own head. A write that cannot land in one world is
 * released there and nowhere else, a world that never gets walked keeps its depth, and the cursor of
 * one world never moves because another world was committed.</p>
 */
class IntentWorldIsolationTest {

    private static final long TICK = 7L;

    @Test
    void aFailingWorldDoesNotHoldTheWritesOfAnother() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 0);
        List<String> applied = new ArrayList<>();
        queue.bindPayload(intent -> {
            if ("a-1".equals(intent.payloadHandle())) {
                return IntentPayload.Outcome.rejected(RejectCode.VERSION_MISMATCH);
            }
            applied.add(intent.dstWorldId());
            return IntentPayload.Outcome.APPLIED;
        });
        queue.enqueue(intent(1L, "world-a", "a-1"));
        queue.enqueue(intent(2L, "world-a", "a-2"));
        queue.enqueue(intent(3L, "world-b", "b-1"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());

        CommitSegment.Pass pass = segment.run(TICK);

        assertEquals(2, pass.steps());
        assertEquals(List.of("world-a", "world-b"), applied,
            "the second write of the failing world is not held by the first one");
        assertEquals(1L, queue.releasedCount());
        assertEquals(0, queue.depth("world-a"));
        assertEquals(0, queue.depth("world-b"));
        assertEquals(2L, segment.cursor("world-a"));
        assertEquals(1L, segment.cursor("world-b"));
        assertEquals(3L, segment.cursor());
        assertEquals(0L, queue.orderViolationCount());
    }

    @Test
    void eachWorldFreezesItsOwnContiguousOrder() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);

        IntentQueue.EnqueueResult first = queue.enqueue(intent(1L, "world-a", "a-1"));
        IntentQueue.EnqueueResult other = queue.enqueue(intent(2L, "world-b", "b-1"));
        IntentQueue.EnqueueResult second = queue.enqueue(intent(3L, "world-a", "a-2"));

        assertEquals(0L, first.handle().frozenOrder());
        assertEquals(0L, other.handle().frozenOrder(),
            "the order is per world, so two worlds both start at zero");
        assertEquals(1L, second.handle().frozenOrder());
        assertEquals(2, queue.shardCount());
    }

    @Test
    void theDepthLimitIsPerWorldSoOneWorldCannotFillTheChannelForAnother() {
        IntentQueue queue = new IntentQueue(() -> 1, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);

        assertTrue(queue.enqueue(intent(1L, "world-a", "a-1")).accepted());
        assertTrue(queue.enqueue(intent(2L, "world-b", "b-1")).accepted());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED,
            queue.enqueue(intent(3L, "world-a", "a-2")).code());
        assertEquals(1, queue.depth("world-a"));
        assertEquals(1, queue.depth("world-b"));
        assertEquals(2, queue.depth());
        assertEquals(1L, queue.rejectedFullCount());
    }

    @Test
    void anOutOfOrderCommitOfOneWorldLeavesTheOthersAlone() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "world-a", "a-1"));
        queue.enqueue(intent(2L, "world-b", "b-1"));

        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, queue.commit("world-a", 5L, TICK).code());
        assertTrue(queue.commit("world-b", 0L, TICK).committed());
        assertTrue(queue.commit("world-a", 0L, TICK).committed());
        assertEquals(0, queue.depth());
        assertEquals(1L, queue.orderViolationCount());
        assertEquals(2L, queue.committedCount());
    }

    private static WriteIntent intent(long id, String world, String handle) {
        return WriteIntent.draft(id, world, world, "block_write", 0L, WriteIntent.UNTRACKED_EPOCH,
            handle, "xdomain", "site:a");
    }
}
