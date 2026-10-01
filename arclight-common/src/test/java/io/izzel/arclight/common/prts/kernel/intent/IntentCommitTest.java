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

    @Test
    void theCommitAppliesWhatItReachesInTheFrozenOrder() {
        List<String> applied = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return null;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));

        CommitOrder outOfOrder = queue.commit(1L, TICK);
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, outOfOrder.code());
        assertEquals(2, queue.depth());

        assertTrue(queue.commit(0L, TICK).committed());
        assertTrue(queue.commit(1L, TICK).committed());
        assertEquals(List.of("first", "second"), applied);
        assertEquals(2L, queue.executedCount());
        assertEquals(2L, queue.committedCount());
        assertEquals(0, queue.depth());
        assertEquals(TICK, queue.lastExecTick());
    }

    @Test
    void theChannelFreezesItsOwnOrderAndARefusedDraftConsumesNone() {
        IntentQueue queue = new IntentQueue(() -> 1);
        queue.bindPayload(intent -> null);
        IntentQueue.EnqueueResult first = queue.enqueue(intent(1L, "first"));
        IntentQueue.EnqueueResult refused = queue.enqueue(intent(2L, "second"));

        assertTrue(first.accepted());
        assertEquals(0L, first.handle().frozenOrder());
        assertFalse(refused.accepted());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, refused.code());
        assertEquals(1L, queue.rejectedFullCount());

        assertTrue(queue.commit(0L, TICK).committed());
        IntentQueue.EnqueueResult third = queue.enqueue(intent(3L, "third"));

        assertTrue(third.accepted());
        assertEquals(1L, third.handle().frozenOrder());
        assertTrue(queue.commit(1L, TICK).committed());
        assertEquals(2L, queue.committedCount());
        assertEquals(0L, queue.orderViolationCount());
        assertEquals(0, queue.depth());
    }

    @Test
    void anOrderOnTheDraftIsReplacedByTheOneTheChannelFreezes() {
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> null);
        queue.enqueue(new WriteIntent(1L, "world", "world", "block_write", 0L, 41L, "handle",
            "xdomain", "site:a"));

        assertFalse(queue.commit(41L, TICK).committed());
        assertTrue(queue.commit(0L, TICK).committed());
    }

    @Test
    void anIntentWithoutAPayloadRefusesTheCommitInsteadOfBeingDropped() {
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.enqueue(intent(1L, "missing"));

        CommitOrder order = queue.commit(0L, TICK);

        assertFalse(order.committed());
        assertEquals(RejectCode.NATIVE_UNDECLARED, order.code());
        assertEquals(1, queue.depth());
        assertEquals(0L, queue.committedCount());
        assertEquals(1L, queue.payloadRefusalCount());
        assertEquals(-1L, queue.lastExecTick());
    }

    @Test
    void aPayloadThatDidNotLandRefusesTheCommit() {
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> RejectCode.VERSION_MISMATCH);
        queue.enqueue(intent(1L, "failing"));

        CommitOrder order = queue.commit(0L, TICK);

        assertFalse(order.committed());
        assertEquals(RejectCode.VERSION_MISMATCH, order.code());
        assertEquals(1, queue.depth());
        assertEquals(1L, queue.payloadRefusalCount());
    }

    @Test
    void anEmptyChannelCommitsNothing() {
        IntentQueue queue = new IntentQueue(() -> 8);

        CommitOrder order = queue.commit(0L, TICK);

        assertTrue(order.committed());
        assertNull(order.code());
        assertEquals(0L, order.commitSeq());
        assertEquals(0L, queue.committedCount());
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L, handle, "xdomain",
            "site:a");
    }
}
