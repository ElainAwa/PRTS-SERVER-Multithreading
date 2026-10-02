/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thread the commit segment may be walked on.
 *
 * <p>The segment is built to run at the end of a tick, on the thread that drives it. That is enforced
 * here: the first caller binds the owner, a walk from any other thread is refused with a code and a
 * count, and the channel is left untouched by the refusal, so a worker cannot drain the deferred
 * writes of the server by reaching the object it holds a reference to.</p>
 */
class CommitThreadOwnershipTest {

    private static final long TICK = 100L;

    @Test
    void aWalkFromAForeignThreadIsRefusedAndConsumesNothing() {
        AtomicInteger applied = new AtomicInteger();
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> {
            applied.incrementAndGet();
            return IntentPayload.Outcome.APPLIED;
        });
        queue.enqueue(intent(1L, "first"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());

        CommitSegment.Pass[] foreign = new CommitSegment.Pass[1];
        Thread worker = new Thread(() -> foreign[0] = segment.run(TICK), "commit-thief");
        worker.start();
        join(worker);

        assertFalse(foreign[0].ran());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, foreign[0].code());
        assertEquals(1L, segment.foreignRuns());
        assertEquals(0, applied.get());
        assertEquals(1, queue.depth());
        assertEquals(0L, segment.passes());
        assertEquals(0L, queue.executedCount());
        assertEquals(0L, segment.cursor());
    }

    @Test
    void theOwnerThreadWalksTheChannel() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());

        assertEquals(1, segment.run(TICK).steps());
        assertEquals(0L, segment.foreignRuns());
        assertEquals(0, queue.depth());
    }

    @Test
    void aSecondOwnerIsCountedAndNeverTakesTheSegmentAway() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());
        Thread other = new Thread(() -> {
        }, "other-thread");

        segment.bindOwnerThread(other);

        assertEquals(1L, segment.ownerConflicts());
        assertTrue(segment.ownerBound());
        assertEquals(1, segment.run(TICK).steps(), "the owner still walks the channel");
    }

    @Test
    void anUnboundSegmentRefusesEveryWalk() {
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        queue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        queue.enqueue(intent(1L, "first"));
        CommitSegment segment = new CommitSegment(queue, () -> true, () -> 8);

        CommitSegment.Pass pass = segment.run(TICK);

        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, pass.code());
        assertEquals(1L, segment.foreignRuns());
        assertEquals(1, queue.depth());
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, handle, "xdomain", "site:a");
    }
}
