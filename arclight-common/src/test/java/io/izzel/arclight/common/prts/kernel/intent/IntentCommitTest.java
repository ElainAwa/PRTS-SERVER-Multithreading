/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The intent channel: the order it freezes, and the one moment a deferred write is applied. */
class IntentCommitTest {

    private static final long TICK = 100L;
    private static final long NEXT_TICK = 101L;

    @Test
    void theCommitAppliesWhatItReachesInTheFrozenOrder() {
        List<String> applied = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return IntentPayload.Outcome.APPLIED;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));

        CommitOrder outOfOrder = queue.commit("world", 1L, TICK);
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, outOfOrder.code());
        assertEquals(2, queue.depth());

        assertTrue(queue.commit("world", 0L, TICK).committed());
        assertTrue(queue.commit("world", 1L, TICK).committed());
        assertEquals(List.of("first", "second"), applied);
        assertEquals(2L, queue.executedCount());
        assertEquals(2L, queue.committedCount());
        assertEquals(0, queue.depth());
        assertEquals(TICK, queue.lastExecTick());
    }

    @Test
    void theChannelFreezesItsOwnOrderAndARefusedDraftConsumesNone() {
        IntentQueue queue = new IntentQueue(() -> 1, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        IntentQueue.EnqueueResult first = queue.enqueue(intent(1L, "first"));
        IntentQueue.EnqueueResult refused = queue.enqueue(intent(2L, "second"));

        assertTrue(first.accepted());
        assertEquals(0L, first.handle().frozenOrder());
        assertFalse(refused.accepted());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, refused.code());
        assertEquals(1L, queue.rejectedFullCount());

        assertTrue(queue.commit("world", 0L, TICK).committed());
        IntentQueue.EnqueueResult third = queue.enqueue(intent(3L, "third"));

        assertTrue(third.accepted());
        assertEquals(1L, third.handle().frozenOrder());
        assertTrue(queue.commit("world", 1L, TICK).committed());
        assertEquals(2L, queue.committedCount());
        assertEquals(0L, queue.orderViolationCount());
        assertEquals(0, queue.depth());
    }

    @Test
    void anOrderOnTheDraftIsReplacedByTheOneTheChannelFreezes() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(new WriteIntent(1L, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, 41L, "handle", "xdomain", "site:a"));

        assertFalse(queue.commit("world", 41L, TICK).committed());
        assertTrue(queue.commit("world", 0L, TICK).committed());
    }

    @Test
    void anIntentWithoutAPayloadIsReleasedInsteadOfHoldingTheChannel() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> "behind".equals(intent.payloadHandle())
            ? IntentPayload.Outcome.APPLIED
            : IntentPayload.Outcome.rejected(RejectCode.NATIVE_UNDECLARED));
        queue.enqueue(intent(1L, "missing"));
        queue.enqueue(intent(2L, "behind"));

        CommitOrder order = queue.commit("world", 0L, TICK);

        assertFalse(order.committed());
        assertTrue(order.released());
        assertEquals(RejectCode.NATIVE_UNDECLARED, order.code());
        assertEquals(1, queue.depth());
        assertEquals(1L, queue.releasedCount());
        assertEquals(0L, queue.committedCount());
        assertEquals(1L, queue.payloadRefusalCount());
        assertEquals(-1L, queue.lastExecTick());

        assertTrue(queue.commit("world", 1L, TICK).committed(),
            "the intent behind an unprocessable head is reachable after it is released");
        assertEquals(0, queue.depth());
    }

    @Test
    void aPayloadThatDidNotLandIsRefusedUntilItsRetryBudgetIsSpent() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 1);
        queue.bindPayload(intent -> IntentPayload.Outcome.retryable(RejectCode.VERSION_MISMATCH));
        queue.enqueue(intent(1L, "failing"));

        CommitOrder order = queue.commit("world", 0L, TICK);

        assertFalse(order.committed());
        assertFalse(order.released());
        assertEquals(RejectCode.VERSION_MISMATCH, order.code());
        assertEquals(1, queue.depth());
        assertEquals(1L, queue.payloadRefusalCount());

        CommitOrder exhausted = queue.commit("world", 0L, NEXT_TICK);

        assertTrue(exhausted.released());
        assertEquals(RejectCode.VERSION_MISMATCH, exhausted.code());
        assertEquals(0, queue.depth());
        assertEquals(1L, queue.retryExhaustedCount());
        assertEquals(1L, queue.releasedCount());
    }

    @Test
    void aFinalRefusalIsReleasedOnTheFirstAttempt() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 4);
        queue.bindPayload(intent -> IntentPayload.Outcome.rejected(RejectCode.VERSION_MISMATCH));
        queue.enqueue(intent(1L, "stale"));

        CommitOrder order = queue.commit("world", 0L, TICK);

        assertTrue(order.released());
        assertEquals(0, queue.depth());
        assertEquals(0L, queue.retryExhaustedCount());
        assertEquals(0L, queue.payloadRefusalCount() - 1L);
    }

    @Test
    void anEmptyChannelCommitsNothing() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);

        CommitOrder order = queue.commit("world", 0L, TICK);

        assertTrue(order.committed());
        assertNull(order.code());
        assertEquals(0L, order.commitSeq());
        assertEquals(0L, queue.committedCount());
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, handle, "xdomain", "site:a");
    }
}
