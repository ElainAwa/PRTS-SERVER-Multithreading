/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The race between a batch that timed out and the batch that was given its slot next: the late
 * worker returns after the slot was reused and must not be able to free it.
 */
class DispatchLeaseTest {

    private static final long TICK = 100L;
    private static final String WORLD = "minecraft:overworld";

    @Test
    void lateWorkerCannotReleaseReusedSlot() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan first = WorkPlan.freeze(TICK, 1L, List.of(view(1)), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(2, "prts-lease-",
            Thread.NORM_PRIORITY, 8, 4), 1, readings, arena);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass firstPass = DispatchPass.dispatch(first, pool, (batch, target, token) -> {
                entered.countDown();
                awaitLatch(go);
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            assertTrue(awaitLatch(entered), "the slow worker never started");
            MergeSegment.Frame firstFrame = merge.merge(firstPass,
                System.nanoTime() + 1_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), "entity", null);

            assertEquals(1, firstFrame.cancelled(), "the deadline did not cancel the slow batch");
            assertEquals(1, firstFrame.redone(), "a cancelled batch was not redone on the tick thread");
            assertTrue(arena.pinPairsHold(), "the timed-out batch did not give its slot back");

            // The next pass is dispatched over the slot the deadline just released.
            WorkPlan second = WorkPlan.freeze(TICK + 1, 1L, List.of(view(1)), 4, 100L);
            DispatchPass secondPass = DispatchPass.dispatch(second, pool, EntityIntegrator.INSTANCE,
                arena, readings, ledger);
            DispatchPass.Entry reused = secondPass.entries().get(0);
            assertNotNull(reused.slot(), "the second pass did not claim a slot");
            assertNotNull(reused.lease(), "the second pass claimed a slot without a lease");
            long newOwner = reused.lease().ownerBatchId();
            long newGeneration = reused.lease().generation();

            // The late worker returns now and releases what it still believes it holds.
            go.countDown();
            assertTrue(awaitStale(arena), "the late release was not refused and counted");

            assertEquals(newOwner, reused.slot().ownerBatchId(),
                "the late release changed the owner of the new batch's slot");
            assertNotEquals(ArenaSlot.State.FREE, reused.slot().state(),
                "the late release freed the new batch's slot");
            assertEquals(newGeneration, reused.slot().generation(),
                "the late release moved the generation of the new batch's slot");
            assertEquals(0L, arena.foreignWrites());
            assertTrue(readings.lateResultDropped() + readings.lateEpochDropped() >= 1L,
                "the late result was not counted");

            MergeSegment.Frame secondFrame = merge.merge(secondPass,
                System.nanoTime() + 2_000_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), "entity", null);
            assertEquals(second.taskCount(), secondFrame.committed());
            assertTrue(secondFrame.hashEqual(),
                "the frame of the reused slot is not the frame the reference produces");
            assertTrue(secondFrame.closureOk());
            assertTrue(arena.pinPairsHold(), "the reused slot was not paired after its merge");
        } finally {
            go.countDown();
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

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitLatch(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static EntityCandidateView view(int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, 0, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }
}
