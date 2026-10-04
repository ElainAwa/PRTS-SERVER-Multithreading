/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The developer-only fault directive: it must inject nothing while undeclared, inject exactly
 * what it declares, and make a delayed worker's release be refused by the reused slot. */
class FaultInjectionTest {

    private static final long TICK = 100L;
    private static final String WORLD = "minecraft:overworld";

    @Test
    void nothingIsInjectedWithoutAReadableDirective() {
        for (String directive : new String[] {null, "", "  ", "delayMs", "delayMs=abc",
            "holdWorld=", "unknown=1"}) {
            FaultInjection.Spec spec = FaultInjection.Spec.parse(directive);
            assertFalse(spec.enabled(), "a directive was accepted without a usable token");
            assertTrue(spec.holdWorlds().isEmpty());
            assertEquals(0L, FaultInjection.workerDelayNanos(spec));
            assertFalse(FaultInjection.holdsMerge(spec, plan(task(WORLD, 7L)), world -> 7L));
            assertFalse(FaultInjection.ownershipFails(spec));
            assertEquals(0L, FaultInjection.ownershipDelayNanos(spec));
            assertFalse(FaultInjection.ownershipEpochBreak(spec));
            assertFalse(FaultInjection.ownershipSkipsIgnored(spec));
            assertFalse(FaultInjection.ownershipDoubleRuns(spec));
            assertFalse(FaultInjection.ownershipThrows(spec));
            assertFalse(FaultInjection.ownershipEntityBreak(spec));
            assertFalse(FaultInjection.ownershipSegmentBreak(spec));
            assertFalse(FaultInjection.ownershipOrdinalBreak(spec));
            assertFalse(FaultInjection.ownershipObserveClaims(spec));
            assertEquals(0, FaultInjection.segmentBreakRow(spec));
        }
        assertFalse(FaultInjection.enabled(), "the process carries no directive in a test run");
        assertEquals(0, FaultInjection.segmentBreakRow(),
            "a row was deviated in the frame digest without a directive");
    }

    @Test
    void theInjectedPauseCoversTheDeclaredNumberOfBatches() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse("delayMs=60,delayBatches=2");
        assertTrue(spec.enabled());
        assertEquals(60_000_000L, FaultInjection.workerDelayNanos(spec));
        assertEquals(60_000_000L, FaultInjection.workerDelayNanos(spec));
        assertEquals(0L, FaultInjection.workerDelayNanos(spec),
            "the pause covered more than the declared number of batches");
        long startedAt = System.nanoTime();
        FaultInjection.pauseWorker(spec);
        assertTrue(System.nanoTime() - startedAt < 20_000_000L,
            "a spent pause still waited in the worker");
    }

    @Test
    void theOwnershipFaultsAreSpentExactlyOnTheDeclaredRows() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse(
            "ownFail=2,ownDelayMs=40,ownDelayRows=1,ownEpochBreak=1,ownSkipIgnored=1,ownDoubleRun=1,"
                + "ownThrow=1");
        assertTrue(spec.enabled());
        assertTrue(FaultInjection.ownershipFails(spec));
        assertTrue(FaultInjection.ownershipFails(spec));
        assertFalse(FaultInjection.ownershipFails(spec), "more rows failed than were declared");
        assertEquals(40_000_000L, FaultInjection.ownershipDelayNanos(spec));
        assertEquals(0L, FaultInjection.ownershipDelayNanos(spec),
            "the ownership pause covered more rows than were declared");
        assertTrue(FaultInjection.ownershipEpochBreak(spec));
        assertFalse(FaultInjection.ownershipEpochBreak(spec),
            "more revalidations were broken than were declared");
        assertTrue(FaultInjection.ownershipSkipsIgnored(spec));
        assertFalse(FaultInjection.ownershipSkipsIgnored(spec),
            "more skips were left uncancelled than were declared");
        assertTrue(FaultInjection.ownershipDoubleRuns(spec));
        assertFalse(FaultInjection.ownershipDoubleRuns(spec),
            "more rows ran twice than were declared");
        assertTrue(FaultInjection.ownershipThrows(spec));
        assertFalse(FaultInjection.ownershipThrows(spec),
            "the worker threw on more rows than were declared");
        assertFalse(FaultInjection.ownershipEntityBreak(),
            "the entity generation of a row was broken without a directive");
        assertFalse(FaultInjection.ownershipSegmentBreak(),
            "the segment generation of a row was broken without a directive");
        assertFalse(FaultInjection.ownershipOrdinalBreak(),
            "the host order was broken without a directive");
        assertFalse(FaultInjection.ownershipObserveClaims(),
            "a row was booked in both sets without a directive");
        assertFalse(FaultInjection.ownershipFails(),
            "the process carries no ownership fault in a test run");
        assertFalse(FaultInjection.ownershipEpochBreak());
        assertFalse(FaultInjection.ownershipSkipsIgnored());
        assertFalse(FaultInjection.ownershipDoubleRuns());
        assertFalse(FaultInjection.ownershipThrows());
    }

    @Test
    void theSegmentFaultsAreSpentExactlyOnTheDeclaredRows() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse(
            "ownEntityBreak=1,ownSegmentBreak=1,ownOrdinalBreak=2,ownObserveClaim=1");
        assertTrue(spec.enabled());
        assertTrue(FaultInjection.ownershipEntityBreak(spec));
        assertFalse(FaultInjection.ownershipEntityBreak(spec),
            "more entity generations were broken than were declared");
        assertTrue(FaultInjection.ownershipSegmentBreak(spec));
        assertFalse(FaultInjection.ownershipSegmentBreak(spec),
            "more segment generations were broken than were declared");
        assertTrue(FaultInjection.ownershipOrdinalBreak(spec));
        assertTrue(FaultInjection.ownershipOrdinalBreak(spec));
        assertFalse(FaultInjection.ownershipOrdinalBreak(spec),
            "more host orders were broken than were declared");
        assertTrue(FaultInjection.ownershipObserveClaims(spec));
        assertFalse(FaultInjection.ownershipObserveClaims(spec),
            "more rows were booked in both sets than were declared");
    }

    @Test
    void theSegmentBreakIsSpentExactlyOnTheDeclaredFramesAndRow() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse("segBreak=2,segBreakRow=5");
        assertTrue(spec.enabled());
        assertEquals(5, FaultInjection.segmentBreakRow(spec));
        assertEquals(5, FaultInjection.segmentBreakRow(spec));
        assertEquals(0, FaultInjection.segmentBreakRow(spec),
            "more frames were deviated than were declared");
        FaultInjection.Spec first = FaultInjection.Spec.parse("segBreak=1");
        assertEquals(1, FaultInjection.segmentBreakRow(first),
            "the deviated row does not default to the first");
    }

    @Test
    void aDeclaredWorldIsHeldUntilItsGenerationChanges() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse("holdWorld=w1|w2");
        assertTrue(spec.enabled());
        assertTrue(spec.holdWorlds().containsAll(List.of("w1", "w2")));
        WorkPlan pass = plan(task("w1", 7L));

        assertTrue(FaultInjection.holdsMerge(spec, pass, world -> 7L),
            "the declared world was not held on its own generation");
        assertTrue(FaultInjection.holdsMerge(spec, pass, world -> -1L),
            "a world between its unload and its reload stopped holding the pass");
        assertFalse(FaultInjection.holdsMerge(spec, pass, world -> 8L),
            "the plan was held after its world came back with a new generation");
        assertFalse(FaultInjection.holdsMerge(spec, pass, world -> 7L),
            "a world was held twice");

        assertFalse(FaultInjection.holdsMerge(spec, plan(task("w9", 3L)), world -> 3L),
            "a world nobody declared was held");
        assertFalse(FaultInjection.holdsMerge(spec, plan(task("w2", 0L)), world -> 0L),
            "a plan with an untracked generation was held");

        WorkPlan dropped = plan(task("w2", 4L));
        assertTrue(FaultInjection.holdsMerge(spec, dropped, world -> 4L),
            "the second declared world was not held on its own plan");
        WorkPlan next = plan(task("w2", 4L));
        assertFalse(FaultInjection.holdsMerge(spec, next, world -> 4L),
            "a plan that replaced a held one was held again");
    }

    @Test
    void anInjectedDelayMakesTheLateWorkerReleaseRefused() {
        FaultInjection.Spec spec = FaultInjection.Spec.parse("delayMs=120,delayBatches=1");
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan first = WorkPlan.freeze(TICK, 1L, List.of(view(2)), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-fault-",
            Thread.NORM_PRIORITY, 8, 4), 1, readings, arena);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass firstPass = DispatchPass.dispatch(first, pool, (batch, target, token) -> {
                FaultInjection.pauseWorker(spec);
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            DispatchPass.Entry firstEntry = firstPass.entries().get(0);
            MergeSegment.Frame firstFrame = merge.merge(firstPass, System.nanoTime() + 1_000_000L,
                arena, readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", null);
            assertEquals(1, firstFrame.cancelled(),
                "the injected delay did not make the batch miss its deadline");
            assertEquals(1L, readings.timeouts(), "the cancelled batch was not counted as a timeout");
            assertTrue(arena.pinPairsHold(), "the timed-out batch did not give its slot back");

            WorkPlan second = WorkPlan.freeze(TICK + 1, 1L, List.of(view(2)), 4, 100L);
            DispatchPass secondPass = DispatchPass.dispatch(second, pool, EntityIntegrator.INSTANCE,
                arena, readings, ledger);
            DispatchPass.Entry reused = secondPass.entries().get(0);
            assertEquals(firstEntry.lease().slotIndex(), reused.lease().slotIndex(),
                "the next batch did not take the slot the deadline released");
            assertNotEquals(firstEntry.lease().generation(), reused.lease().generation(),
                "the slot was handed over without moving its generation");

            assertTrue(awaitStale(arena), "the late release was not refused and counted");
            assertEquals(reused.lease().ownerBatchId(), reused.slot().ownerBatchId(),
                "the late release changed the owner of the new batch's slot");
            assertNotEquals(ArenaSlot.State.FREE, reused.slot().state(),
                "the late release freed the new batch's slot");
            assertEquals(0L, arena.foreignWrites());
            assertTrue(readings.lateResultDropped() + readings.lateEpochDropped() >= 1L,
                "the late result was not counted");

            MergeSegment.Frame secondFrame = merge.merge(secondPass,
                System.nanoTime() + 2_000_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), "entity", null);
            assertEquals(second.taskCount(), secondFrame.committed(),
                "the batch that took the reused slot was not committed");
            assertTrue(secondFrame.closureOk());
            assertTrue(arena.pinPairsHold(), "the reused slot was not paired after its merge");
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    private static boolean awaitStale(ArenaLedger arena) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (arena.staleReleases() > 0L) {
                return true;
            }
            sleep(2L);
        }
        return arena.staleReleases() > 0L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static WorkPlan plan(WorkTask... tasks) {
        return new WorkPlan(TICK, 1L, List.of(), List.of(tasks), TICK + 1);
    }

    private static WorkTask task(String worldId, long worldEpoch) {
        return new WorkTask(1L, worldId, "r0.0", 1L, 0, 1, 1L, worldEpoch, 0, "r0.0");
    }

    private static EntityCandidateView view(int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, 0, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }
}