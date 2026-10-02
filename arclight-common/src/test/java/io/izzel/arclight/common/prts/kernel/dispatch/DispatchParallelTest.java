/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkBody.NonRetryableFault;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkBody.RetryableFault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parallel dispatch of one tick: the plan, the pool, the deadline and the merge. */
class DispatchParallelTest {

    private static final long TICK = 100L;

    @Test
    void aPlanHoldsEveryRegionOfATickOnExactlyOneTask() {
        EntityCandidateView view = view("world", 8);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view), 4, 1L);

        Set<String> regions = new HashSet<>();
        for (WorkTask task : plan.tasks()) {
            assertTrue(regions.add(task.worldId() + "|" + task.regionId()),
                "a region was frozen into two tasks");
            assertTrue(task.entityCount() > 0);
        }
        assertEquals(plan.taskCount(), regions.size());
    }

    @Test
    void aWorkerRunsTheBatchAndTheMergeCommitsItOnce() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view("world", 8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8, 1);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity");

            assertNotNull(frame);
            assertEquals(plan.taskCount(), frame.committed());
            assertTrue(frame.closureOk());
            assertTrue(frame.hashEqual());
            assertTrue(readings.execByThread("prts-worker-0")
                + readings.execByThread("prts-worker-1") > 0);
            assertEquals(readings.dispatched(), readings.executed());
            assertTrue(arena.pinPairsHold());
            assertEquals(List.of("prts-worker"), pool.threadClasses().stream()
                .map(WorkerPool.ThreadClass::threadClass).distinct().toList());
            assertTrue(pool.threadClasses().get(0).exemptFlags() == 0L);
        } finally {
            pool.shutdown(true, true, 500L);
        }
    }

    @Test
    void aBatchThatMissesTheDeadlineIsCancelledCountedAndRedone() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view("world", 4)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 4, 1);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                sleep(150L);
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 1_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity");

            assertEquals(1L, readings.timeouts());
            assertEquals(1L, readings.cancelled());
            assertEquals(1, frame.cancelled());
            assertEquals(1, frame.redone());
            assertEquals(plan.taskCount(), frame.committed());
            long before = readings.lateResultDropped();
            sleep(250L);
            assertTrue(readings.lateResultDropped() > before,
                "a result after the deadline must be dropped with a count");
            assertTrue(arena.pinPairsHold());
        } finally {
            pool.shutdown(true, true, 500L);
        }
    }

    @Test
    void theThreeFailureKindsHaveThreeTerminalStatuses() throws Exception {
        assertEquals(TaskOutcome.Status.RETRIED, outcomeOf(new RetryableFault("retry"), 0));
        assertEquals(TaskOutcome.Status.FELLBACK, outcomeOf(new NonRetryableFault("no"), 0));
        assertEquals(TaskOutcome.Status.FAILED, outcomeOf(new IllegalStateException("hard"), 0));
    }

    private static TaskOutcome.Status outcomeOf(Throwable fault, int retryBudget)
        throws InterruptedException {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view("world-" + fault.getClass()
            .getSimpleName(), 2)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 4, retryBudget);
        try {
            DispatchPass pass = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                if (fault instanceof RetryableFault retryable) {
                    throw retryable;
                }
                if (fault instanceof NonRetryableFault nonRetryable) {
                    throw nonRetryable;
                }
                throw new IllegalStateException("hard", fault);
            }, arena, readings, ledger);
            List<TaskOutcome> outcomes = pass.awaitAll(System.nanoTime() + 2_000_000_000L);
            assertEquals(1, outcomes.size());
            return outcomes.get(0).status();
        } finally {
            pool.shutdown(true, true, 500L);
        }
    }

    @Test
    void aFullQueueFallsBackToTheTickThread() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkerPool pool = pool(readings, arena, 1, 1);
        try {
            WorkPlan first = WorkPlan.freeze(TICK, 1L, List.of(view("world-a", 2)), 4, 1L);
            DispatchPass firstPass = DispatchPass.dispatch(first, pool, (batch, target, token) -> {
                sleep(120L);
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            WorkPlan second = WorkPlan.freeze(TICK, 1L, List.of(view("world-b", 2)), 4, 1L);
            DispatchPass secondPass = DispatchPass.dispatch(second, pool, EntityIntegrator.INSTANCE,
                arena, readings, ledger);

            assertNull(secondPass.entries().get(0).handle());
            assertNotNull(secondPass.entries().get(0).immediate());
            assertEquals(1L, readings.backpressure());
            firstPass.awaitAll(System.nanoTime() + 2_000_000_000L);
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void theClosureHoldsAndASecondCommitOfOneBatchIsRefused() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view("world", 4)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8, 1);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity");
            assertTrue(frame.closureOk());

            long batchId = plan.tasks().get(0).batchId() + 1000L;
            ledger.register(batchId);
            assertTrue(ledger.markCommitted(batchId));
            assertFalse(ledger.markCommitted(batchId));
            assertEquals(1L, ledger.duplicateCommits());
            assertEquals(TaskLedger.BatchState.COMMITTED, ledger.state(batchId));
            assertFalse(ledger.markDropped(batchId, null));
            assertEquals(1L, ledger.droppedWithoutCode());
        } finally {
            pool.shutdown(true, true, 500L);
        }
    }

    @Test
    void theMainSwitchIsOffAndAnEmptyPlanDispatchesNothing() {
        Boolean declared = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .features().get(KernelSettings.DISPATCH_PARALLEL);
        assertEquals(Boolean.FALSE, declared);
        assertEquals(0, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .numbers().get(KernelSettings.WORKER_COUNT).defaultValue());
        assertEquals(32, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .numbers().get(KernelSettings.WORKER_QUEUE_CAP).defaultValue());
        assertEquals(4, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .numbers().get(KernelSettings.WORKER_BATCH_CHUNKS).defaultValue());
        assertEquals(0, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .numbers().get(KernelSettings.WORKER_DEADLINE_GRACE_MS).defaultValue());
        assertEquals(1, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .numbers().get(KernelSettings.WORKER_RETRY_BUDGET).defaultValue());

        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        WorkPlan empty = WorkPlan.freeze(TICK, 1L,
            List.of(EntityCandidateView.builder("empty", 1L).build()), 4, 1L);
        assertTrue(empty.empty());
        readings.noteTasks(empty.taskCount());
        assertEquals(0L, readings.tasksTotal());
        assertEquals(0L, readings.dispatched());
        assertEquals(0L, readings.executed());
        assertEquals(0, arena.pinnedCount());
        assertFalse(KernelSettings.dispatchParallel());
    }

    @Test
    void theWorkerCountIsDerivedAndBounded() {
        assertEquals(1, DispatchSettings.derivedWorkerCount(2));
        assertEquals(3, DispatchSettings.derivedWorkerCount(4));
        assertEquals(4, DispatchSettings.derivedWorkerCount(64));
        int derived = DispatchSettings.workerCount(0, 64);
        assertTrue(derived >= 1 && derived <= 4);
        assertEquals(8, DispatchSettings.workerCount(99, 64));
        assertEquals(1, DispatchSettings.workerCount(1, 64));
    }

    private static WorkerPool pool(DispatchReadings readings, ArenaLedger arena, int queueCap,
                                   int retryBudget) {
        return WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY,
            queueCap, 4), retryBudget, readings, arena);
    }

    private static EntityCandidateView view(String worldId, int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, i % 2, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
