/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsChunkSorterSubmit;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The identity the sorter submit point mints: the function the sorter queues is wrapped around a
 * task the ledger knows, and the task it names is the one that runs. The same entry with no kernel
 * installed, or for a mailbox placed on no world, mints nothing and hands the function back. */
class PipelineBatchIdentityTest {

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "main";
    private static final long CELL = ChunkPos.asLong(4, -3);
    private static final long TICK = 100L;
    private static final long PLAN = 7L;

    @AfterEach
    void clearSeams() {
        PrtsPipelineBatches.uninstall();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
    }

    @Test
    void theSubmitPointMintsATaskAndWrapsTheFunctionItEnqueues() {
        Rig rig = new Rig();
        AtomicInteger bodies = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> delegate = body(bodies);

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.mailbox, delegate, CELL);

        assertNotSame(delegate, wrapped, "the function the sorter queues is the wrapper");
        assertEquals(1L, rig.ledger.mintedCount(), "the submit point minted exactly one task");
        rig.offer(wrapped);
        PrtsPipelineBatches.enqueued(wrapped);
        PipelineBatchLedger.BatchId batch = rig.ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        List<PipelineBatchLedger.TrackedTask> tasks = rig.ledger.tasksOf(batch);
        assertEquals(1, tasks.size());
        assertEquals(CELL, tasks.get(0).id().cell());
        assertEquals(MAILBOX, tasks.get(0).id().mailbox());
        assertEquals(WORLD, tasks.get(0).id().worldId());
        assertEquals(TICK, tasks.get(0).id().mintTick());

        rig.drainSorter();
        rig.drainSorter();
        rig.drainMain();

        assertEquals(1, bodies.get(), "the wrapped function produced the body the sorter ran");
        assertInstanceOf(PipelineBatchLedger.Done.class, rig.ledger.stateOf(tasks.get(0)));
        assertSame(batch, tasks.get(0).batch(), "the body ran under the batch the freeze cut");
        assertEquals(0L, rig.ledger.anomalyCount());
        assertEquals(0L, rig.ledger.earlyRunCount());
        assertEquals(0L, rig.ledger.lateRunCount());
    }

    @Test
    void withoutAKernelTheSubmitKeepsItsFunctionAndMintsNoIdentity() {
        Rig rig = new Rig();
        PrtsPipelineBatches.uninstall();
        AtomicInteger bodies = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> delegate = body(bodies);

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.mailbox, delegate, CELL);

        assertSame(delegate, wrapped, "no kernel installed means no wrapper");
        assertEquals(0L, rig.ledger.mintedCount());
        rig.offer(wrapped);
        rig.drainSorter();
        rig.drainSorter();
        rig.drainMain();
        assertEquals(1, bodies.get(), "the task still runs, exactly as it was submitted");
        assertEquals(0L, rig.ledger.mintedCount());
    }

    @Test
    void aMailboxNotPlacedOnAWorldMintsNoIdentity() {
        Rig rig = new Rig();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        AtomicInteger bodies = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> delegate = body(bodies);

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.mailbox, delegate, CELL);

        assertSame(delegate, wrapped, "a mailbox with no world cannot name one");
        assertEquals(0L, rig.ledger.mintedCount());
        rig.offer(wrapped);
        rig.drainSorter();
        rig.drainSorter();
        rig.drainMain();
        assertEquals(1, bodies.get());
        assertEquals(0L, rig.ledger.mintedCount());
    }

    @Test
    void aReleaseThatClearsTheQueueCancelsTheResidentTask() {
        Rig rig = new Rig();
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.mailbox, body(bodies), CELL);
        rig.offer(wrapped);
        PrtsPipelineBatches.enqueued(wrapped);
        PipelineBatchLedger.TrackedTask task = rig.cut();

        rig.releases.tell(ChunkTaskPriorityQueueSorter.release(callbacks::incrementAndGet, CELL, true));
        PrtsChunkSorterSubmit.release(rig.mailbox, CELL, true);
        rig.drainSorter();

        assertEquals(1L, rig.ledger.queuedClearedCount(), "the resident task is cancelled");
        assertEquals(1, callbacks.get(), "the sorter still runs the release callback");
        assertEquals(0, bodies.get(), "a task the queue cleared never produces a body");
        assertInstanceOf(PipelineBatchLedger.Cancelled.class, rig.ledger.stateOf(task));
        assertEquals(PipelineBatchLedger.CancelPoint.QUEUED_CLEARED,
            ((PipelineBatchLedger.Cancelled) rig.ledger.stateOf(task)).point());
    }

    @Test
    void aReleaseThatKeepsTheQueueDoesNotCancelTheResidentTask() {
        Rig rig = new Rig();
        AtomicInteger bodies = new AtomicInteger();
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.mailbox, body(bodies), CELL);
        rig.offer(wrapped);
        PrtsPipelineBatches.enqueued(wrapped);
        PipelineBatchLedger.TrackedTask task = rig.cut();

        PrtsChunkSorterSubmit.release(rig.mailbox, CELL, false);
        assertEquals(0L, rig.ledger.queuedClearedCount());
        assertInstanceOf(PipelineBatchLedger.Claimed.class, rig.ledger.stateOf(task));
        assertEquals(PipelineBatchLedger.Place.QUEUED,
            ((PipelineBatchLedger.Claimed) rig.ledger.stateOf(task)).place());

        rig.drainSorter();
        rig.drainSorter();
        rig.drainMain();
        assertEquals(1, bodies.get(), "a release that keeps the queue leaves the task to run");
        assertInstanceOf(PipelineBatchLedger.Done.class, rig.ledger.stateOf(task));
    }

    private static Function<ProcessorHandle<Unit>, Runnable> body(AtomicInteger bodies) {
        return reply -> () -> {
            bodies.incrementAndGet();
            reply.tell(Unit.INSTANCE);
        };
    }

    /** The real sorter and the real mailbox of one test; the executors are stepped by hand. */
    private static final class Rig {

        private final ManualExecutor sorterExecutor = new ManualExecutor();
        private final ManualExecutor mainExecutor = new ManualExecutor();
        private final ProcessorMailbox<Runnable> mailbox;
        private final ChunkTaskPriorityQueueSorter sorter;
        private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> queue;
        private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Release> releases;
        private final PipelineBatchLedger ledger = new PipelineBatchLedger(64, 100);

        private Rig() {
            mainExecutor.defer();
            mailbox = ProcessorMailbox.create(mainExecutor, MAILBOX);
            sorter = new ChunkTaskPriorityQueueSorter(List.of(mailbox), sorterExecutor, 8);
            queue = sorter.getProcessor(mailbox, true);
            releases = sorter.getReleaseProcessor(mailbox);
            sorterExecutor.defer();
            PrtsPipelineRows.bindMailboxWorlds(Map.of(mailbox, WORLD));
            PrtsPipelineBatches.install(ledger, () -> TICK);
        }

        private void offer(Function<ProcessorHandle<Unit>, Runnable> task) {
            queue.tell(ChunkTaskPriorityQueueSorter.message(task, CELL, () -> 1));
        }

        private void drainSorter() {
            sorterExecutor.drainOne();
        }

        private void drainMain() {
            mainExecutor.drainOne();
        }

        private PipelineBatchLedger.TrackedTask cut() {
            PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
            if (batch == null) {
                throw new AssertionError("the ledger holds no task of this mailbox");
            }
            return ledger.tasksOf(batch).get(0);
        }
    }

    /** One executor the test steps by hand: a mailbox run queued here stays queued until drained. */
    private static final class ManualExecutor implements Executor {

        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean inline = true;

        @Override
        public void execute(Runnable command) {
            if (inline) {
                command.run();
            } else {
                queue.add(command);
            }
        }

        private void defer() {
            inline = false;
        }

        private void drainOne() {
            Runnable next = queue.poll();
            if (next == null) {
                throw new AssertionError("the executor holds no task to drain");
            }
            next.run();
        }
    }
}
