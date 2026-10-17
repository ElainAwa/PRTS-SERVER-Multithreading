/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three interleavings the bounded prototype left open: a window that outran its range, a
 * registration that a reset overtook, and an exception that skipped the cleanup. */
class PipelineBatchRepairTest {

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
    void theRangeOfABatchIsVisibleWhileItsBindingIsStillForming() throws Exception {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gate(ledger, "batches", blocked, release);
        PipelineBatchLedger.TrackedTask first =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        PipelineBatchLedger.TrackedTask second =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 2L, TICK);
        ledger.enqueued(first);
        ledger.enqueued(second);
        AtomicReference<PipelineBatchLedger.BatchId> cut = new AtomicReference<>();
        Thread freezer = new Thread(() -> cut.set(ledger.freeze(WORLD, MAILBOX, PLAN, TICK)));
        freezer.start();
        assertTrue(blocked.await(5, TimeUnit.SECONDS), "the freeze never reached the binding write");

        // Every claim is installed and no binding is published: the window may not open over nothing.
        PipelineBatchLedger.Claimed claim = assertInstanceOf(PipelineBatchLedger.Claimed.class,
            ledger.stateOf(first));
        PipelineBatchLedger.BatchId batch = claim.batch();
        assertNull(cut.get(), "the freeze has not returned, so its binding is not published yet");
        assertTrue(ledger.runStart(first), "the claimed task opens the window of its plan");
        assertEquals(2, ledger.cellsOf(batch).length,
            "the range of a window that is already open is visible before the binding is published");
        assertEquals(2, ledger.tasksOf(batch).size(),
            "the members of that batch are visible with it");

        release.countDown();
        freezer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(freezer.isAlive(), "the freeze did not finish");
        assertEquals(2, ledger.cellsOf(batch).length, "the published binding names the same range");
        assertEquals(2, ledger.tasksOf(batch).size());
        assertSame(batch, cut.get());
    }

    @Test
    void aMintOvertakenByAResetDoesNotJoinTheNextGeneration() throws Exception {
        PipelineBatchLedger ledger = new PipelineBatchLedger(1, 5);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gate(ledger, "pending", blocked, release);
        AtomicReference<PipelineBatchLedger.TrackedTask> first = new AtomicReference<>();
        Thread minter = new Thread(() -> first.set(ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, 0L)));
        minter.start();
        assertTrue(blocked.await(5, TimeUnit.SECONDS), "the mint never reached the index insert");
        CountDownLatch resetStarted = new CountDownLatch(1);
        CountDownLatch resetDone = new CountDownLatch(1);
        Thread resetter = new Thread(() -> {
            resetStarted.countDown();
            ledger.reset();
            resetDone.countDown();
        });
        resetter.start();
        assertTrue(resetStarted.await(5, TimeUnit.SECONDS), "the reset never started");
        boolean resetRanUnderThePausedMint = resetDone.await(300, TimeUnit.MILLISECONDS);
        release.countDown();
        minter.join(TimeUnit.SECONDS.toMillis(5));
        resetter.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(minter.isAlive(), "the mint did not finish");
        assertFalse(resetter.isAlive(), "the reset did not finish");

        assertFalse(resetRanUnderThePausedMint,
            "a reset overtaken by a registration cannot advance the generation under it");
        assertFalse(first.get().live(), "the overtaken mint is detached, not carried over");
        assertEquals(0, ledger.pendingCount(), "the next generation starts with an empty index");
        assertEquals(0L, ledger.trackedCount(), "and with an empty count");

        PipelineBatchLedger.TrackedTask second = ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, 100L);
        assertTrue(second.tracked());
        assertEquals(1, ledger.pendingCount(), "the pending limit of the new generation is intact");
        assertEquals(1L, ledger.trackedCount());
        ledger.noteTick(105L);
        assertEquals(1, ledger.pendingCount(), "a young mint keeps its entry");
        ledger.noteTick(106L);
        assertEquals(0, ledger.pendingCount());
        assertEquals(0L, ledger.trackedCount(), "the sweep never drives the count below zero");
        assertEquals(1L, ledger.expiredCount());
    }

    @Test
    void aClaimedTaskThatNeverEnqueuesIsSweptAtTheTick() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(1, 5);
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 7L, 0L);
        assertNotNull(ledger.freeze(WORLD, MAILBOX, PLAN, 0L));
        PipelineBatchLedger.Claimed claim = assertInstanceOf(PipelineBatchLedger.Claimed.class,
            ledger.stateOf(task));
        assertEquals(PipelineBatchLedger.Place.MINTED, claim.place());
        assertEquals(1, ledger.pendingCount());

        ledger.noteTick(6L);

        assertEquals(0, ledger.pendingCount(),
            "a claim on a task no enqueue reached does not hold its slot for good");
        assertEquals(1L, ledger.expiredCount());
        assertEquals(0L, ledger.trackedCount());
        assertFalse(task.live());
        assertFalse(ledger.runStart(task), "a swept claim never opens a window");
        assertTrue(ledger.mint(PROCESSOR, WORLD, MAILBOX, 7L, 6L).tracked(),
            "the slot the swept task held is reusable");
    }

    @Test
    void aReleaseCancelsAClaimedResidentTask() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);

        ledger.released(PROCESSOR, 1L, true);

        PipelineBatchLedger.Cancelled cancelled = assertInstanceOf(PipelineBatchLedger.Cancelled.class,
            ledger.stateOf(task));
        assertSame(batch, cancelled.batch());
        assertEquals(1L, ledger.queuedClearedCount());
        assertEquals(0L, ledger.releaseAfterHandoffCount());
        assertEquals(0, ledger.pendingCount());
    }

    @Test
    void theOneReadShapeSkipsAndTheRereadingShapeCancels() {
        PipelineBatchLedger.BatchId batch =
            new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 1L, PLAN);
        AtomicReference<PipelineBatchLedger.TaskState> skipping =
            new AtomicReference<>(new PipelineBatchLedger.Queued());
        assertFalse(cancel(skipping, batch, false),
            "the shape that sets once walks away from a lost set");
        assertInstanceOf(PipelineBatchLedger.Claimed.class, skipping.get(),
            "that is the skip the release must not leave a resident task in");

        AtomicReference<PipelineBatchLedger.TaskState> rereading =
            new AtomicReference<>(new PipelineBatchLedger.Queued());
        assertTrue(cancel(rereading, batch, true),
            "the shape that re-reads completes the cancellation");
        PipelineBatchLedger.Cancelled cancelled = assertInstanceOf(PipelineBatchLedger.Cancelled.class,
            rereading.get());
        assertSame(batch, cancelled.batch());
    }

    @Test
    void anApplyFailureClearsTheIndexAndKeepsItsException() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(1, 5);
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, 0L);
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> {
            throw new IllegalStateException("apply");
        };
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PipelineBatchWrapper.function(ledger, task, delegate);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> wrapped.apply(null));

        assertEquals("apply", thrown.getMessage(), "the failure of the delegate reaches the sorter");
        assertInstanceOf(PipelineBatchLedger.DoneUnclaimed.class, ledger.stateOf(task),
            "no body was produced, so the task leaves as a task that never ran");
        assertEquals(1L, ledger.notRunnableCount());
        assertEquals(0, ledger.pendingCount(), "a failed apply does not keep the slot");
        assertEquals(0L, ledger.trackedCount());
        assertTrue(ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, 1L).tracked(),
            "the slot the failed task held is reusable");
    }

    @Test
    void aThrowingEndTapKeepsTheBodyFailurePrimaryAndStillClearsTheLedger() {
        ThrowingTap tap = new ThrowingTap(null, new IllegalStateException("end"));
        PrtsPipelineRows.installOwnerTap(tap);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(PROCESSOR, WORLD));
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> {
            throw new IllegalStateException("body");
        };
        Runnable body = PipelineBatchWrapper.function(ledger, task, delegate).apply(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, body::run);

        assertEquals("body", thrown.getMessage(), "the end tap may not stand in for the body failure");
        assertEquals(1, thrown.getSuppressed().length, "the end tap failure is kept, not swallowed");
        assertEquals("end", thrown.getSuppressed()[0].getMessage());
        assertEquals(1, tap.begins);
        assertEquals(1, tap.ends, "the span still pairs");
        assertEquals(0, ledger.pendingCount(), "the ledger cleanup runs even when the end tap threw");
        assertNull(ledger.runningTask(), "the running task is released");
        assertEquals(new PipelineBatchLedger.Done(batch), ledger.stateOf(task));
        assertEquals(0L, ledger.anomalyCount());
    }

    @Test
    void aThrowingBeginTapStillRunsTheBodyAndReportsItsOwnFailure() {
        ThrowingTap tap = new ThrowingTap(new IllegalStateException("begin"), null);
        PrtsPipelineRows.installOwnerTap(tap);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(PROCESSOR, WORLD));
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(PROCESSOR, WORLD, MAILBOX, 1L, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        AtomicInteger runs = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> runs::incrementAndGet;
        Runnable body = PipelineBatchWrapper.function(ledger, task, delegate).apply(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, body::run);

        assertEquals("begin", thrown.getMessage(), "the watcher failure is reported, not swallowed");
        assertEquals(1, runs.get(), "a refused span does not skip the submitted body");
        assertEquals(1, tap.ends, "the end still pairs with the begin that was attempted");
        assertEquals(0, ledger.pendingCount());
        assertNull(ledger.runningTask());
        assertEquals(new PipelineBatchLedger.Done(batch), ledger.stateOf(task));
    }

    // The cancellation as the two shapes do it. The first shape sets once and walks away when the
    // set loses; the second re-reads and stays with the task while it is still resident. The freeze
    // step is the claim that lands between the read and the set, which no seam exposes on the
    // production path, so the two shapes are driven here over the same immutable states.
    private static boolean cancel(AtomicReference<PipelineBatchLedger.TaskState> state,
                                  PipelineBatchLedger.BatchId batch, boolean reread) {
        for (;;) {
            PipelineBatchLedger.TaskState now = state.get();
            if (now instanceof PipelineBatchLedger.Queued) {
                state.set(new PipelineBatchLedger.Claimed(batch, PipelineBatchLedger.Place.QUEUED));
            }
            PipelineBatchLedger.TaskState next = now instanceof PipelineBatchLedger.Claimed claimed
                ? new PipelineBatchLedger.Cancelled(claimed.batch(),
                    PipelineBatchLedger.CancelPoint.QUEUED_CLEARED)
                : new PipelineBatchLedger.CancelledUnclaimed(
                    PipelineBatchLedger.CancelPoint.QUEUED_CLEARED);
            if (state.compareAndSet(now, next)) {
                return true;
            }
            if (!reread) {
                return false;
            }
        }
    }

    private static void gate(PipelineBatchLedger ledger, String field, CountDownLatch entered,
                             CountDownLatch release) throws Exception {
        Field slot = PipelineBatchLedger.class.getDeclaredField(field);
        slot.setAccessible(true);
        slot.set(ledger, new GatedSkipList(entered, release));
    }

    /** A map whose first insert stops before the write, which is the exact window a freeze or a mint
     * is observed in; every later insert proceeds. */
    private static final class GatedSkipList extends ConcurrentSkipListMap<Long, Object> {

        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        private GatedSkipList(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public Object put(Long key, Object value) {
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "the fixture latch timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return super.put(key, value);
        }
    }

    /** A watcher whose begin or end refuses the span it is asked to cover. */
    private static final class ThrowingTap implements PrtsPipelineRows.MailboxOwnerTap {

        private final RuntimeException begin;
        private final RuntimeException end;
        private int begins;
        private int ends;

        private ThrowingTap(RuntimeException begin, RuntimeException end) {
            this.begin = begin;
            this.end = end;
        }

        @Override
        public void ownerTask(String world, String mailbox) {
        }

        @Override
        public void ownerRound(String world, String mailbox) {
        }

        @Override
        public void ownerTaskBegin(String world, String mailbox, Object token) {
            begins++;
            if (begin != null) {
                throw begin;
            }
        }

        @Override
        public void ownerTaskEnd(String world, String mailbox, Object token) {
            ends++;
            if (end != null) {
                throw end;
            }
        }
    }
}
