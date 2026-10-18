/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.observe.ChunkDemandObserver;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status a chunk demand of one pipeline task names, joined to the task and batch it was made
 * under before the run-start seal. The fixture uses the real ledger, the real demand seam and the
 * real task objects; no server is started and no world is read. */
class DemandTaskStatusBridgeTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long CELL2 = ChunkPos.asLong(4, -7);
    private static final long CELL3 = ChunkPos.asLong(5, -7);
    private static final long EPOCH = 11L;
    private static final String DOMAIN = PipelineRoundJobs.DOMAIN;

    private PipelineBatchLedger ledger;
    private DemandShadowCandidates candidates;

    @BeforeEach
    void setUp() {
        ledger = new PipelineBatchLedger(8, 100);
        candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
    }

    @AfterEach
    void tearDown() {
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    private PipelineBatchLedger.TrackedTask submit(long cell) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, cell, TICK);
        ledger.enqueued(task);
        return task;
    }

    private PipelineBatchLedger.BatchId freeze(long plan) {
        return ledger.freeze(WORLD, MAILBOX, plan, TICK);
    }

    private PipelineBatchLedger.TrackedTask running(long cell, long plan) {
        PipelineBatchLedger.TrackedTask task = submit(cell);
        assertNotNull(freeze(plan));
        assertTrue(ledger.runStart(task));
        return task;
    }

    private List<PipelineBatchLedger.TrackedTask> runningAll(long... cells) {
        List<PipelineBatchLedger.TrackedTask> tasks = new ArrayList<>();
        for (long cell : cells) {
            tasks.add(submit(cell));
        }
        assertNotNull(freeze(PLAN));
        for (PipelineBatchLedger.TrackedTask task : tasks) {
            assertTrue(ledger.runStart(task));
        }
        return tasks;
    }

    private long demand(PipelineBatchLedger.TrackedTask task, String status) {
        return PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(task.id().cell()),
            ChunkPos.getZ(task.id().cell()), status, true, task);
    }

    @SuppressWarnings("unchecked")
    private static void setState(PipelineBatchLedger.TrackedTask task,
                                 PipelineBatchLedger.TaskState state) throws Exception {
        Field field = PipelineBatchLedger.TrackedTask.class.getDeclaredField("state");
        field.setAccessible(true);
        ((AtomicReference<PipelineBatchLedger.TaskState>) field.get(task)).set(state);
    }

    private static PipelineBatchLedger.TrackedTask forged(PipelineBatchLedger.TrackedTask source,
                                                          PipelineBatchLedger.BatchId batch)
        throws Exception {
        Constructor<PipelineBatchLedger.TrackedTask> constructor =
            PipelineBatchLedger.TrackedTask.class.getDeclaredConstructor(
                PipelineBatchLedger.TaskId.class, Object.class, boolean.class, long.class);
        constructor.setAccessible(true);
        Field epoch = PipelineBatchLedger.TrackedTask.class.getDeclaredField("epoch");
        epoch.setAccessible(true);
        PipelineBatchLedger.TrackedTask copy = constructor.newInstance(source.id(), source.processor(),
            true, epoch.getLong(source));
        setState(copy, new PipelineBatchLedger.Running(batch));
        return copy;
    }

    @Test
    void theSameTaskJoinsItsOwnDemandBeforeTheRunStartSeal() {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        PipelineBatchLedger.BatchId batch = task.batch();
        assertNotNull(batch);
        assertSame(task, PrtsPipelineBatches.currentTask());

        long token = PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(CELL),
            ChunkPos.getZ(CELL), "full", true, PrtsPipelineBatches.currentTask());
        DemandShadowCandidates.TaskStatus status = candidates.statusFor(task);

        assertTrue(status.present());
        assertEquals("full", status.status());
        assertEquals(task.id().ordinal(), status.stamp().taskOrdinal());
        assertEquals(WORLD, status.stamp().world());
        assertEquals(MAILBOX, status.stamp().mailbox());
        assertEquals(CELL, status.stamp().cell());
        assertEquals(TICK, status.stamp().mintTick());
        assertEquals(batch.freezeOrdinal(), status.stamp().batchFreezeOrdinal());
        assertEquals(batch.planSequence(), status.stamp().planSequence());
        assertEquals(token, status.stamp().demandToken());

        BiiSealPrototype.FreezePoint point = new BiiSealPrototype.FreezePoint(world -> EPOCH);
        FrozenDeclarations.FreezeKey key = new FrozenDeclarations.FreezeKey("lifecycle-332", 1L);
        String nodeKey = PipelineRoundJobs.batchKeyOf(batch);
        BiiSealPrototype.Seal seal = new BiiSealPrototype.Seal(point, key, PLAN,
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, nodeKey, 0)));
        BiiSealPrototype.Outcome identity = seal.identity(batch, DOMAIN, nodeKey, 0);
        assertTrue(identity.declared());
        BiiSealPrototype.Outcome sealed = seal.range(identity.node(), ledger, ledger.cellsOf(batch),
            CELL, status.status(), 0L);
        assertEquals(BiiSealPrototype.Code.SEALED, sealed.code());
        assertEquals("full", sealed.sealed().targetStatus());
    }

    @Test
    void anotherTaskOfTheSameCellAndStatusDoesNotGetThatStatus() {
        List<PipelineBatchLedger.TrackedTask> tasks = runningAll(CELL, CELL);
        PipelineBatchLedger.TrackedTask first = tasks.get(0);
        PipelineBatchLedger.TrackedTask second = tasks.get(1);
        long firstToken = demand(first, "full");

        assertFalse(candidates.statusFor(second).present());

        long secondToken = demand(second, "full");
        DemandShadowCandidates.TaskStatus status = candidates.statusFor(second);
        assertTrue(status.present());
        assertEquals(secondToken, status.stamp().demandToken());
        assertNotEquals(firstToken, status.stamp().demandToken());
        assertEquals(second.id().ordinal(), status.stamp().taskOrdinal());
    }

    @Test
    void anotherTaskWithAnotherCellDoesNotGetThatStatus() {
        List<PipelineBatchLedger.TrackedTask> tasks = runningAll(CELL, CELL2);
        demand(tasks.get(0), "full");

        assertFalse(candidates.statusFor(tasks.get(1)).present());
    }

    @Test
    void twoBatchesDoNotLendEachOtherAStatus() {
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.BatchId firstBatch = freeze(PLAN);
        assertNotNull(firstBatch);
        assertTrue(ledger.runStart(first));
        long firstToken = demand(first, "full");

        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId secondBatch = freeze(PLAN + 1L);
        assertNotNull(secondBatch);
        assertTrue(ledger.runStart(second));
        long secondToken = demand(second, "full");

        DemandShadowCandidates.TaskStatus firstStatus = candidates.statusFor(first);
        DemandShadowCandidates.TaskStatus secondStatus = candidates.statusFor(second);
        assertTrue(firstStatus.present());
        assertTrue(secondStatus.present());
        assertEquals(firstBatch.freezeOrdinal(), firstStatus.stamp().batchFreezeOrdinal());
        assertEquals(secondBatch.freezeOrdinal(), secondStatus.stamp().batchFreezeOrdinal());
        assertEquals(firstToken, firstStatus.stamp().demandToken());
        assertEquals(secondToken, secondStatus.stamp().demandToken());
        assertNotEquals(firstStatus.stamp().batchFreezeOrdinal(),
            secondStatus.stamp().batchFreezeOrdinal());
    }

    @Test
    void aTaskWhoseRunningBatchMovedIsRefused() throws Exception {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        demand(task, "full");
        PipelineBatchLedger.BatchId other = new PipelineBatchLedger.BatchId(WORLD, MAILBOX,
            task.batch().freezeOrdinal() + 1L, PLAN + 1L);
        setState(task, new PipelineBatchLedger.Running(other));

        assertFalse(candidates.statusFor(task).present());
    }

    @Test
    void twoDifferentStatusesOnOneTaskAreAmbiguous() {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        demand(task, "full");
        demand(task, "features");

        assertFalse(candidates.statusFor(task).present());
        assertEquals(1L, candidates.statusConflicts());

        demand(task, "full");
        assertFalse(candidates.statusFor(task).present());
    }

    @Test
    void aDemandAfterTheSealIsNotAdopted() {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        demand(task, "full");
        DemandShadowCandidates.TaskStatus sealed = candidates.statusFor(task);
        assertEquals("full", sealed.status());

        assertTrue(demand(task, "features") > 0L);
        DemandShadowCandidates.TaskStatus after = candidates.statusFor(task);

        assertEquals("full", after.status());
        assertNotEquals("features", after.status());
        assertEquals(1L, candidates.statusLate());
    }

    @Test
    void aTaskThatEndedDoesNotAnswer() {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        demand(task, "full");
        ledger.runEnd(task);

        assertFalse(candidates.statusFor(task).present());
    }

    @Test
    void aResetTaskAndANewTaskOfTheSameCellDoNotShareAStatus() {
        PipelineBatchLedger.TrackedTask old = running(CELL, PLAN);
        demand(old, "full");
        assertTrue(candidates.statusFor(old).present());
        long oldOrdinal = old.id().ordinal();

        ledger.reset();
        assertFalse(old.live());
        assertFalse(candidates.statusFor(old).present());

        PipelineBatchLedger.TrackedTask fresh = running(CELL, PLAN + 1L);
        assertTrue(fresh.id().ordinal() > oldOrdinal);
        assertFalse(candidates.statusFor(fresh).present());
        assertTrue(demand(fresh, "full") > 0L);
        assertTrue(candidates.statusFor(fresh).present());
        assertFalse(candidates.statusFor(old).present());
    }

    @Test
    void aForgedTaskWithTheSameIdFieldsIsRefused() throws Exception {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        demand(task, "full");
        PipelineBatchLedger.TrackedTask forged = forged(task, task.batch());

        assertNotSame(task, forged);
        assertEquals(task.id(), forged.id());
        assertFalse(candidates.statusFor(forged).present());
        assertTrue(candidates.statusFor(task).present());
    }

    @Test
    void aFullRegisterRefusesTheNewTaskAndKeepsTheOldOnes() {
        candidates = new DemandShadowCandidates(2);
        PrtsChunkDemand.install(candidates);
        List<PipelineBatchLedger.TrackedTask> tasks = runningAll(CELL, CELL2, CELL3);
        long first = demand(tasks.get(0), "full");
        long second = demand(tasks.get(1), "full");
        long third = demand(tasks.get(2), "full");

        assertTrue(first > 0L && second > 0L && third > 0L, "the ask itself is not blocked");
        for (long token : new long[] {first, second, third}) {
            PrtsChunkDemand.registrationConfirmed(WORLD, "full", token, true);
        }
        assertTrue(candidates.statusFor(tasks.get(0)).present());
        assertTrue(candidates.statusFor(tasks.get(1)).present());
        assertFalse(candidates.statusFor(tasks.get(2)).present(), "no old status is reused");
        assertEquals(1L, candidates.statusOverflows());
        assertEquals(2, candidates.statusCapacity());
        assertEquals(2, candidates.statusHeld());
        assertEquals(3, candidates.held(), "the demand record itself is still captured");
    }

    @Test
    void aFinishedTaskIsReclaimedForTheNextTask() {
        candidates = new DemandShadowCandidates(2);
        PrtsChunkDemand.install(candidates);
        PipelineBatchLedger.TrackedTask first = running(CELL, PLAN);
        PipelineBatchLedger.TrackedTask second = running(CELL2, PLAN + 1L);
        demand(first, "full");
        demand(second, "full");
        assertEquals(2, candidates.statusHeld());

        ledger.runEnd(first);
        PipelineBatchLedger.TrackedTask third = running(CELL3, PLAN + 2L);
        assertTrue(demand(third, "full") > 0L);

        assertFalse(candidates.statusFor(first).present());
        assertTrue(candidates.statusFor(third).present());
        assertEquals(2, candidates.statusHeld());
        assertEquals(1L, candidates.statusReclaimed());
    }

    @Test
    void repeatedDemandsKeepOneStatusAndDoNotOverflow() {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        for (int index = 0; index < 1000; index++) {
            assertTrue(demand(task, "full") > 0L);
        }

        DemandShadowCandidates.TaskStatus status = candidates.statusFor(task);
        assertEquals("full", status.status());
        assertEquals(1, candidates.statusHeld());
        assertEquals(0L, candidates.statusOverflows());
        assertEquals(999L, candidates.statusDuplicates());
    }

    @Test
    void aDemandWithoutARunningTaskRegistersNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        assertTrue(demand(task, "full") > 0L);

        assertFalse(candidates.statusFor(task).present());
        assertEquals(1L, candidates.statusRefused());
    }

    @Test
    void theObserverCarriesTheTaskToTheRegister() {
        PrtsChunkDemand.install(null);
        ChunkDemandObserver observer = new ChunkDemandObserver();
        observer.attach();
        observer.attachSecondPath(candidates);
        try {
            PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
            long token = demand(task, "full");
            DemandShadowCandidates.TaskStatus status = candidates.statusFor(task);
            assertTrue(status.present());
            assertEquals(token, status.stamp().demandToken());
            assertEquals("full", status.status());
        } finally {
            observer.detach();
        }
    }

    @Test
    void onlyTheTaskObjectIsAccepted() {
        assertFalse(candidates.statusFor(null).present());
        assertFalse(candidates.statusFor("task").present());
        assertFalse(candidates.statusFor(Long.valueOf(1L)).present());
    }

    @Test
    void manyThreadsDemandTheSameStatusWithoutLosingIt() throws Exception {
        PipelineBatchLedger.TrackedTask task = running(CELL, PLAN);
        int threads = 4;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < perThread; round++) {
                    demand(task, "full");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30L, TimeUnit.SECONDS);
        }
        pool.shutdown();

        DemandShadowCandidates.TaskStatus status = candidates.statusFor(task);
        assertTrue(status.present());
        assertEquals("full", status.status());
        assertEquals(1, candidates.statusHeld());
        assertEquals(0L, candidates.statusOverflows());
        assertEquals((long) threads * perThread - 1L, candidates.statusDuplicates());
    }
}
