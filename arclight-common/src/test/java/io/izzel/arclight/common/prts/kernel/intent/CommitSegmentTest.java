/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

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
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> {
            throw new IllegalStateException("a segment that is off must not apply a payload");
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = segment(queue, false, queue::capacity);

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
        assertEquals(0L, segment.walkNanos(), "a segment that held still was timed as walking");
    }

    @Test
    void theSwitchOnDrainsTheFrozenOrderAndPublishesTheTick() {
        List<String> applied = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return IntentPayload.Outcome.APPLIED;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = segment(queue, true, queue::capacity);

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
        assertTrue(segment.walkNanos() > 0L, "the walk left no reading of its own duration");
    }

    @Test
    void aRefusalEndsTheWalkAndLeavesTheHeadInPlace() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        AtomicInteger attempts = new AtomicInteger();
        queue.bindPayload(intent -> attempts.incrementAndGet() == 2
            ? IntentPayload.Outcome.retryable(RejectCode.VERSION_MISMATCH)
            : IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = segment(queue, true, queue::capacity);

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
    void aDirectoryFailureKeepsTheIntentAndPayloadPairedForRetry() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        AtomicInteger attempts = new AtomicInteger();
        String handle = payloads.bind("block_write", () -> attempts.incrementAndGet() > 1);
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(payloads);
        queue.enqueue(intent(1L, handle));
        CommitSegment segment = segment(queue, true, queue::capacity);

        CommitSegment.Pass refused = segment.run(TICK);

        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals(1, queue.depth());
        assertEquals(1, payloads.pendingCount());
        assertEquals(0, refused.steps());

        CommitSegment.Pass committed = segment.run(NEXT_TICK);

        assertNull(committed.code());
        assertEquals(1, committed.steps());
        assertEquals(0, queue.depth());
        assertEquals(0, payloads.pendingCount());
        assertEquals(1L, payloads.appliedCount());
    }

    @Test
    void theBudgetEndsTheWalkWithoutLosingWhatIsLeft() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment segment = segment(queue, true, () -> 1);

        assertEquals(1, segment.run(TICK).steps());
        assertEquals(1, queue.depth());
        assertEquals(1, segment.run(NEXT_TICK).steps());
        assertEquals(0, queue.depth());
        assertEquals(2L, queue.committedCount());
    }

    @Test
    void resettingReadingsDoesNotRewindTheFrozenOrder() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        CommitSegment segment = segment(queue, true, queue::capacity);

        assertEquals(1, segment.run(TICK).steps());
        segment.reset();
        queue.enqueue(intent(2L, "second"));

        assertEquals(1, segment.run(NEXT_TICK).steps());
        assertEquals(0, queue.depth());
        assertEquals(0L, queue.orderViolationCount());
        assertEquals(2L, segment.cursor());
        assertEquals(1L, segment.passes());
    }

    private static CommitSegment segment(IntentQueue queue, boolean enabled, IntSupplier budget) {
        CommitSegment segment = new CommitSegment(queue, () -> enabled, budget);
        segment.bindOwnerThread(Thread.currentThread());
        return segment;
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, handle, "xdomain", "site:a");
    }
}
