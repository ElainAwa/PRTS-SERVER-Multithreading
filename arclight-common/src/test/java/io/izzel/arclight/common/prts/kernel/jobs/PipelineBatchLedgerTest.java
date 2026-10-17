/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The task state the batch is published in, the cancellation boundary of the sorter queue, the
 * unchanged execution of a task the ledger stopped observing, and the pairing a legacy tap gets. */
class PipelineBatchLedgerTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "main";
    private static final long PLAN = 7L;
    private static final long TICK = 100L;
    private static final Object PROCESSOR = new Object();

    @AfterEach
    void clearTaps() {
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
    }

    @Test
    void theBatchIsPublishedByTheSameCasThatClaimsTheTask() throws Exception {
        Object legacyBatch = new Object();
        AtomicReference<String> phase = new AtomicReference<>("QUEUED");
        AtomicReference<Object> legacyField = new AtomicReference<>();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Object> seenByRunner = new AtomicReference<>();
        Thread claimer = new Thread(() -> {
            phase.compareAndSet("QUEUED", "CLAIMED");
            claimed.countDown();
            await(resume);
            legacyField.set(legacyBatch);
        });
        Thread runner = new Thread(() -> {
            await(claimed);
            if (phase.compareAndSet("CLAIMED", "RUNNING")) {
                seenByRunner.set(legacyField.get());
            }
            resume.countDown();
        });
        claimer.start();
        runner.start();
        claimer.join(TimeUnit.SECONDS.toMillis(5));
        runner.join(TimeUnit.SECONDS.toMillis(5));
        assertNull(seenByRunner.get(),
            "the old shape can publish RUNNING while the batch is still null");

        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        AtomicReference<PipelineBatchLedger.BatchId> claimedBatch = new AtomicReference<>();
        Thread freezer = new Thread(() -> claimedBatch.set(ledger.freeze(WORLD, MAILBOX, PLAN, TICK)));
        freezer.start();
        PipelineBatchLedger.TaskState observed = awaitClaim(ledger, task);
        PipelineBatchLedger.Claimed claim = assertInstanceOf(PipelineBatchLedger.Claimed.class,
            observed, "a claimed task names its batch in the state itself");
        assertNotNull(claim.batch(), "the claim cannot be observed without its batch");
        assertTrue(ledger.runStart(task), "the claimed task opens the window of its plan");
        PipelineBatchLedger.Running running = assertInstanceOf(PipelineBatchLedger.Running.class,
            ledger.stateOf(task));
        assertSame(claim.batch(), running.batch(), "the running state carries the claimed batch");
        ledger.runEnd(task);
        assertEquals(new PipelineBatchLedger.Done(claim.batch()), ledger.stateOf(task));
        freezer.join(TimeUnit.SECONDS.toMillis(5));
        assertSame(claimedBatch.get(), claim.batch());
    }

    @Test
    void aTaskThatRanBeforeAnyFreezeIsNeverDeclared() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        assertFalse(ledger.runStart(task), "a task no freeze reached runs unclaimed");
        ledger.runEnd(task);

        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK), "a task already done is not cut");
        assertInstanceOf(PipelineBatchLedger.DoneUnclaimed.class, ledger.stateOf(task));
        assertEquals(1L, ledger.earlyRunCount());
        assertEquals(0L, ledger.pendingCount());
        assertEquals(1L, ledger.emptyFreezeCount());
    }

    @Test
    void aTaskCancelledWhileResidentIsNeverDeclared() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        ledger.released(PROCESSOR, 1L, true);

        assertInstanceOf(PipelineBatchLedger.CancelledUnclaimed.class, ledger.stateOf(task));
        assertEquals(1L, ledger.queuedClearedCount());
        assertEquals(0L, ledger.pendingCount(), "a terminal task leaves the index");
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));
        assertFalse(ledger.runStart(task), "a cancelled task never opens a window");
    }

    @Test
    void aReleaseBeforeTheEnqueueLeavesTheTaskToRun() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.released(PROCESSOR, 1L, true);

        assertInstanceOf(PipelineBatchLedger.Minted.class, ledger.stateOf(task));
        assertEquals(1L, ledger.releaseBeforeEnqueueCount());
        assertEquals(0L, ledger.queuedClearedCount(), "a task not yet in the queue is not cleared");
        ledger.enqueued(task);
        assertInstanceOf(PipelineBatchLedger.Queued.class, ledger.stateOf(task));
        assertNotNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK),
            "the task still enqueues and is cut for the batch");
        assertTrue(ledger.runStart(task));
        ledger.runEnd(task);
        assertInstanceOf(PipelineBatchLedger.Done.class, ledger.stateOf(task));
    }

    @Test
    void aReleaseAfterTheHandoffDoesNotCancelTheTask() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        ledger.handedOff(task);
        ledger.released(PROCESSOR, 1L, true);

        assertInstanceOf(PipelineBatchLedger.HandedOff.class, ledger.stateOf(task));
        assertEquals(1L, ledger.releaseAfterHandoffCount());
        assertEquals(0L, ledger.queuedClearedCount(),
            "a body already handed to its mailbox cannot be cleared from the sorter queue");
        assertFalse(ledger.runStart(task), "a task no freeze reached runs unclaimed");
        ledger.runEnd(task);
        assertInstanceOf(PipelineBatchLedger.DoneUnclaimed.class, ledger.stateOf(task));
    }

    @Test
    void aReleaseOfAClaimedResidentTaskKeepsTheBatchItOverDeclared() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        ledger.released(PROCESSOR, 1L, true);

        PipelineBatchLedger.Cancelled cancelled =
            assertInstanceOf(PipelineBatchLedger.Cancelled.class, ledger.stateOf(task));
        assertSame(batch, cancelled.batch(), "the cancelled task keeps the batch it declared");
        assertEquals(1L, ledger.queuedClearedCount());
        assertEquals(0L, ledger.releaseAfterHandoffCount());
    }

    @Test
    void aTaskAboveThePendingBoundKeepsItsOwnFunctionAndStillRuns() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(2, 100);
        PipelineBatchLedger.TrackedTask first =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        PipelineBatchLedger.TrackedTask second =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        PipelineBatchLedger.TrackedTask third =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        assertTrue(first.tracked());
        assertTrue(second.tracked());
        assertFalse(third.tracked());
        assertEquals(1L, ledger.overflowedCount());
        assertEquals(2L, ledger.pendingCount());

        AtomicInteger runs = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> runs::incrementAndGet;
        assertSame(delegate, PipelineBatchWrapper.function(ledger, third, delegate),
            "an untracked task is handed back as the very function that was submitted");
        assertInstanceOf(PipelineBatchLedger.Minted.class, ledger.stateOf(third),
            "an untracked task is not advanced by the ledger");
        assertNotNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));

        for (PipelineBatchLedger.TrackedTask task : List.of(first, second)) {
            Function<ProcessorHandle<Unit>, Runnable> wrapped =
                PipelineBatchWrapper.function(ledger, task, delegate);
            assertFalse(wrapped == delegate);
            wrapped.apply(null).run();
        }
        assertEquals(2, runs.get(), "the original bodies run unchanged");
        assertEquals(0L, ledger.pendingCount(), "a terminal task leaves the index");
        assertEquals(0L, ledger.anomalyCount());
    }

    @Test
    void aMintedTaskNoEnqueueReachedIsSweptAtTheTick() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(8, 5);
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, 0L);
        ledger.noteTick(3L);
        assertEquals(1L, ledger.pendingCount(), "a young mint keeps its index entry");
        ledger.noteTick(6L);
        assertEquals(0L, ledger.pendingCount());
        assertEquals(1L, ledger.expiredCount());
        assertFalse(task.live());
        ledger.enqueued(task);
        assertFalse(ledger.runStart(task));
        assertInstanceOf(PipelineBatchLedger.Minted.class, ledger.stateOf(task),
            "a swept task runs outside the ledger");
    }

    @Test
    void oneCellCanCarryMoreTasksThanTheCellBound() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 100);
        for (int index = 0; index < 20; index++) {
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        }
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        assertEquals(1, ledger.cellsOf(batch).length);
        assertEquals(20L, ledger.claimedTaskCount(),
            "the cell bound is not a bound on the tasks of a cell");
        assertEquals(0L, ledger.deferredCount());

        PipelineBatchLedger second = new PipelineBatchLedger(64, 100);
        for (int index = 0; index < 17; index++) {
            second.mint(PROCESSOR, WORLD, MAILBOX, 100L + index, TICK);
        }
        PipelineBatchLedger.BatchId cut = second.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(cut);
        assertEquals(PipelineBatchLedger.MAX_CELLS, second.cellsOf(cut).length);
        assertEquals(PipelineBatchLedger.MAX_CELLS, second.claimedTaskCount());
        assertEquals(1L, second.deferredCount(), "the seventeenth cell waits for a later freeze");
    }

    @Test
    void aSecondFreezeDoesNotReclaimATaskOfTheFirst() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        PipelineBatchLedger.BatchId first = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        PipelineBatchLedger.BatchId second = ledger.freeze(WORLD, MAILBOX, PLAN + 1, TICK);
        assertNotNull(first);
        assertNull(second, "a claimed task is left for no later cut");
        assertSame(first, task.batch(), "a task belongs to at most one batch");
        assertEquals(1L, ledger.closedBatchCount(), "the superseded batch is closed");
        PipelineBatchLedger.BatchReport report = ledger.reports().get(0);
        assertSame(first, report.batch());
        assertEquals(1, report.unfinished());
    }

    @Test
    void aBatchALiveTaskStillNeedsOutlivesTheHistoryBound() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        List<PipelineBatchLedger.TrackedTask> tasks = new ArrayList<>();
        List<PipelineBatchLedger.BatchId> batches = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            String mailbox = "box-" + index;
            PipelineBatchLedger.TrackedTask task =
                ledger.mint(PROCESSOR, WORLD, mailbox, index, TICK);
            ledger.enqueued(task);
            PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, mailbox, PLAN, TICK);
            assertNotNull(batch);
            tasks.add(task);
            batches.add(batch);
        }
        PipelineBatchLedger.BatchId first = batches.get(0);
        long[] range = ledger.cellsOf(first);
        assertArrayEquals(new long[] {0L}, range,
            "the range of a batch a still pending task will open is not dropped by the bound");
        assertTrue(ledger.runStart(tasks.get(0)), "the claimed task still opens its own batch");
        assertArrayEquals(range, ledger.cellsOf(first),
            "the open window keeps the range it opened over");
        ledger.runEnd(tasks.get(0));

        PipelineBatchLedger.TrackedTask extra =
            ledger.mint(PROCESSOR, WORLD, "box-extra", 99L, TICK);
        ledger.enqueued(extra);
        assertNotNull(ledger.freeze(WORLD, "box-extra", PLAN, TICK));
        assertEquals(0, ledger.cellsOf(first).length,
            "a binding no pending task still names is dropped by the bound");
        assertEquals(1, ledger.cellsOf(batches.get(1)).length,
            "the bindings the other pending tasks still need are kept");
    }

    @Test
    void terminalStatesLeaveTheIndexAndResetDropsTheRest() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask done =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        PipelineBatchLedger.TrackedTask cancelled =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 2L, TICK);
        PipelineBatchLedger.TrackedTask left =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 3L, TICK);
        ledger.enqueued(done);
        ledger.runStart(done);
        ledger.runEnd(done);
        ledger.enqueued(cancelled);
        ledger.released(PROCESSOR, 2L, true);
        assertEquals(1L, ledger.pendingCount(), "only the unended task keeps its entry");

        ledger.reset();
        assertEquals(0L, ledger.pendingCount());
        assertEquals(1L, ledger.resetCount());
        assertEquals(0L, ledger.mintedCount());
        assertFalse(left.live(), "a reset detaches what it drops");
        ledger.runEnd(left);
        assertEquals(0L, ledger.anomalyCount(), "a detached task accepts no transition");
        assertEquals(0L, ledger.emptyFreezeCount(), "the readings after a reset start empty");
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));
    }

    @Test
    void aLegacyTapGetsTheBeginAndEndOfTheSpanItNeverNamed() {
        LegacyTap tap = new LegacyTap();
        PrtsPipelineRows.installOwnerTap(tap);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(PROCESSOR, WORLD));
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        assertNotNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));

        Function<ProcessorHandle<Unit>, Runnable> delegate =
            reply -> () -> tap.runs.incrementAndGet();
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PipelineBatchWrapper.function(ledger, task, delegate);
        Runnable body = wrapped.apply(null);
        body.run();

        assertEquals(1, tap.runs.get());
        assertEquals(List.of(WORLD + "|" + MAILBOX), tap.begins,
            "the two-argument watcher is called through the token-carrying default");
        assertEquals(tap.begins, tap.ends);
        assertEquals(0, ledger.pendingCount());
    }

    @Test
    void aThrowingBodyStillClosesTheLegacyTapSpan() {
        LegacyTap tap = new LegacyTap();
        PrtsPipelineRows.installOwnerTap(tap);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(PROCESSOR, WORLD));
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        assertNotNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> {
            throw new IllegalStateException("body");
        };
        Runnable body = PipelineBatchWrapper.function(ledger, task, delegate).apply(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, body::run);
        assertEquals("body", thrown.getMessage(), "the exception reaches the caller unchanged");
        assertEquals(tap.begins, tap.ends, "an exception path still ends the span it began");
        assertInstanceOf(PipelineBatchLedger.Done.class, ledger.stateOf(task));
        assertEquals(0L, ledger.anomalyCount());
    }

    private static PipelineBatchLedger.TaskState awaitClaim(PipelineBatchLedger ledger,
                                                            PipelineBatchLedger.TrackedTask task) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            PipelineBatchLedger.TaskState state = ledger.stateOf(task);
            if (state instanceof PipelineBatchLedger.Claimed) {
                return state;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the claim was never published: " + ledger.stateOf(task));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the fixture latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** A watcher written against the two-argument face, which it never extends. */
    private static final class LegacyTap implements PrtsPipelineRows.MailboxOwnerTap {

        private final List<String> begins = new CopyOnWriteArrayList<>();
        private final List<String> ends = new CopyOnWriteArrayList<>();
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicBoolean unused = new AtomicBoolean();

        @Override
        public void ownerTask(String world, String mailbox) {
            unused.set(true);
        }

        @Override
        public void ownerRound(String world, String mailbox) {
            unused.set(true);
        }

        @Override
        public void ownerTaskBegin(String world, String mailbox) {
            begins.add(world + "|" + mailbox);
        }

        @Override
        public void ownerTaskEnd(String world, String mailbox) {
            ends.add(world + "|" + mailbox);
        }
    }
}
