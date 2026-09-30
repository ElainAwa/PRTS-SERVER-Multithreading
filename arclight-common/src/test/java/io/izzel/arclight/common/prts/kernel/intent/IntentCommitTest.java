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

/** The commit segment: the one moment a deferred write is applied. */
class IntentCommitTest {

    @Test
    void theCommitAppliesWhatItReachesInTheFrozenOrder() {
        List<String> applied = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8, () -> true);
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return null;
        });
        queue.enqueue(intent(1L, "first", 0L));
        queue.enqueue(intent(2L, "second", 1L));

        CommitOrder outOfOrder = queue.commit(1L);
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, outOfOrder.code());
        assertEquals(2, queue.depth());

        assertTrue(queue.commit(0L).committed());
        assertTrue(queue.commit(1L).committed());
        assertEquals(List.of("first", "second"), applied);
        assertEquals(2L, queue.executedCount());
        assertEquals(2L, queue.committedCount());
        assertEquals(0, queue.depth());
        assertEquals(0L, queue.shapeOnlyCount());
        assertEquals("execute", queue.commitMode());
    }

    @Test
    void anIntentWithoutAPayloadRefusesTheCommitInsteadOfBeingDropped() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> true);
        queue.enqueue(intent(1L, "missing", 0L));

        CommitOrder order = queue.commit(0L);

        assertFalse(order.committed());
        assertEquals(RejectCode.NATIVE_UNDECLARED, order.code());
        assertEquals(1, queue.depth());
        assertEquals(0L, queue.committedCount());
        assertEquals(1L, queue.payloadRefusalCount());
    }

    @Test
    void aPayloadThatDidNotLandRefusesTheCommit() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> true);
        queue.bindPayload(intent -> RejectCode.VERSION_MISMATCH);
        queue.enqueue(intent(1L, "failing", 0L));

        CommitOrder order = queue.commit(0L);

        assertFalse(order.committed());
        assertEquals(RejectCode.VERSION_MISMATCH, order.code());
        assertEquals(1, queue.depth());
        assertEquals(1L, queue.payloadRefusalCount());
    }

    @Test
    void theShapeModeProvesTheOrderWithoutApplyingAnything() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> false);
        queue.bindPayload(intent -> {
            throw new IllegalStateException("the shape mode must not apply a payload");
        });
        queue.enqueue(intent(1L, "shape", 0L));

        CommitOrder order = queue.commit(0L);

        assertTrue(order.committed());
        assertNull(order.code());
        assertEquals(0, queue.depth());
        assertEquals(1L, queue.committedCount());
        assertEquals(1L, queue.shapeOnlyCount());
        assertEquals(0L, queue.executedCount());
        assertEquals("shape", queue.commitMode());
    }

    @Test
    void anEmptyChannelCommitsNothing() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> true);

        CommitOrder order = queue.commit(0L);

        assertTrue(order.committed());
        assertEquals(0L, order.commitSeq());
        assertEquals(0L, queue.committedCount());
    }

    private static WriteIntent intent(long id, String handle, long frozenOrder) {
        return new WriteIntent(id, "world", "world", "block_write", 0L, frozenOrder, handle,
            "xdomain", "site:a");
    }
}
