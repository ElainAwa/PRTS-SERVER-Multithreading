/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The commit segment: the switch that decides whether the channel is walked at all. */
class CommitSegmentTest {

    private static final long TICK = 100L;
    private static final long NEXT_TICK = 101L;

    @Test
    void theSwitchOffConsumesNothingAndLeavesTheHeadWhereItWasFrozen() {
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> {
            throw new IllegalStateException("a segment that is off must not apply a payload");
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = new CommitSegment(queue, () -> false, queue::capacity);

        CommitSegment.Pass pass = segment.run(TICK);

        assertFalse(pass.ran());
        assertEquals(0, pass.steps());
        assertEquals("hold", segment.mode());
        assertEquals(0L, segment.passes());
        assertEquals(0L, segment.cursor());
        assertEquals(2, queue.depth());
        assertEquals(0L, queue.executedCount());
        assertEquals(0L, queue.committedCount());
        assertEquals(2L, queue.enqueuedCount());
    }

    @Test
    void theSwitchOnDrainsTheFrozenOrderAndPublishesTheTick() {
        List<String> applied = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return null;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = new CommitSegment(queue, () -> true, queue::capacity);

        CommitSegment.Pass pass = segment.run(TICK);

        assertTrue(pass.ran());
        assertNull(pass.code());
        assertEquals(2, pass.steps());
        assertEquals(List.of("first", "second"), applied);
        assertEquals(2L, queue.executedCount());
        assertEquals(0, queue.depth());
        assertEquals(2L, segment.cursor());
        assertEquals(2L, segment.steps());
        assertEquals(1L, segment.passes());
        assertEquals(TICK, queue.lastExecTick());
        assertEquals(0L, queue.orderViolationCount());
    }

    @Test
    void aRefusalEndsTheWalkAndLeavesTheHeadInPlace() {
        IntentQueue queue = new IntentQueue(() -> 8);
        AtomicInteger attempts = new AtomicInteger();
        queue.bindPayload(intent -> attempts.incrementAndGet() == 2
            ? RejectCode.VERSION_MISMATCH : null);
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = new CommitSegment(queue, () -> true, queue::capacity);

        CommitSegment.Pass refused = segment.run(TICK);

        assertTrue(refused.ran());
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals(1, refused.steps());
        assertEquals(1, queue.depth());
        assertEquals(1L, segment.cursor());
        assertEquals(1L, segment.refusals());
        assertEquals(TICK, queue.lastExecTick());

        assertEquals(1, segment.run(NEXT_TICK).steps());
        assertEquals(0, queue.depth());
        assertEquals(2L, queue.committedCount());
    }

    @Test
    void theBudgetEndsTheWalkWithoutLosingWhatIsLeft() {
        IntentQueue queue = new IntentQueue(() -> 8);
        queue.bindPayload(intent -> null);
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 1);

        assertEquals(1, segment.run(TICK).steps());
        assertEquals(1, queue.depth());
        assertEquals(1, segment.run(NEXT_TICK).steps());
        assertEquals(0, queue.depth());
        assertEquals(2L, queue.committedCount());
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L, handle, "xdomain",
            "site:a");
    }
}
