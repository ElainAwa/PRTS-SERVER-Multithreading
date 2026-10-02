/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch check of the write-back leg: what a worker answered is judged against the same pure step
 * recomputed on the tick thread, and a batch the check refuses is redone here instead of entering the
 * frame. A body that writes a value the step does not produce is the negative leg of that check: the
 * check must catch it, never call it equal, and the frame must still be the frame the reference arm
 * produces.
 */
class DispatchVerifyTest {

    private static final long TICK = 100L;
    private static final String WORLD = "minecraft:overworld";

    @Test
    void anHonestBatchIsTrustedAndNothingIsRedone() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);

            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", writeBack(readings));

            assertEquals(0L, readings.verifyMismatch(), "an honest batch was refused");
            assertEquals(0L, readings.tasksOnMain());
            assertEquals(0L, readings.redoNanos(), "a fallback was counted for an honest batch");
            assertTrue(readings.verifyNanos() > 0L, "the check left no reading of its own");
            assertTrue(frame.hashEqual(), "the two arms must agree on an honest frame");
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void aBatchWhoseSlotWasWrittenWithAnotherValueIsRefusedAndRedoneHere() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, corruptFirstRow(), arena, readings,
                ledger);

            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", writeBack(readings));

            assertEquals(plan.taskCount(), readings.verifyMismatch(),
                "a batch whose values differ from the same step was not refused");
            assertEquals(plan.taskCount(), readings.tasksOnMain(),
                "a refused batch was not redone on the tick thread");
            assertTrue(readings.redoNanos() > 0L, "the fallback left no reading of its own");
            assertEquals(plan.taskCount(), frame.committed());
            assertTrue(frame.hashEqual(),
                "the frame a refused batch was redone into is not the frame the reference produces");
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void theCheckRefusesAValueThatDiffersOnlyInTheLastRowOfTheBatch() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, corruptLastRow(), arena, readings,
                ledger);

            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", writeBack(readings));

            assertEquals(plan.taskCount(), readings.verifyMismatch(),
                "a difference in the last row of a batch was not caught");
            assertTrue(frame.hashEqual());
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void theMovedHostStepIsTheOneTheHostRuns() {
        ArenaScratch scratch = new ArenaScratch();
        EntityIntegrator.integrateRangeSerial(hostStepView(), 0, 5, scratch);

        // An armour stand the host does not move keeps its position, and its speed is dragged by the
        // double literal the host multiplies by.
        assertEquals(4.0, scratch.posX(0), "a row the host does not move was advanced");
        assertEquals(0.1 * 0.98, scratch.velX(0), "the drag is not the one the host applies");

        // The small speed zeroing runs on the dragged value and keeps the boundary: below it the
        // component is zero, at it the component stays.
        assertEquals(0.0, scratch.velX(1), "a speed below the small movement distance was kept");
        assertEquals(0.0031 * 0.98, scratch.velX(2), "a speed at the boundary was zeroed");

        // An item off the ground advances by the velocity it had, and its horizontal drag is the
        // float literal the host states while its vertical drag is the double one.
        assertEquals(8.0 + 0.1, scratch.posX(3), "an item in the air was not advanced");
        assertEquals(0.1 * (double) 0.98F, scratch.velX(3),
            "an item in the air is dragged by the literal the host states");
        assertEquals(0.2 * 0.98, scratch.velY(3), "the vertical drag of an item is not the host's");
        assertNotEquals(0.1 * 0.98, 0.1 * (double) 0.98F,
            "the two drag literals must stay distinguishable");

        // A row whose host step is not one of the moved ones keeps the plain carry untouched.
        assertEquals(64.0 + 0.2, scratch.posY(4), "the plain step no longer advances the position");
        assertEquals(0.2, scratch.velY(4), "the plain step no longer carries the velocity");
    }

    private static EntityCandidateView hostStepView() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        builder.add(0, 0, 0, 4.0, 64.0, 8.0, 30.0, 15.0, 0.1, 0.0, 0.0,
            EntityIntegrator.STEP_LIVING_NO_PHYSICS);
        builder.add(1, 1, 0, 4.0, 64.0, 8.0, 30.0, 15.0, 0.003, 0.0, 0.0,
            EntityIntegrator.STEP_LIVING_NO_PHYSICS);
        builder.add(2, 2, 0, 4.0, 64.0, 8.0, 30.0, 15.0, 0.0031, 0.0, 0.0,
            EntityIntegrator.STEP_LIVING_NO_PHYSICS);
        builder.add(3, 3, 0, 8.0, 64.0, 8.0, 0.0, 0.0, 0.1, 0.2, 0.0,
            EntityIntegrator.STEP_ITEM_AIR);
        builder.add(4, 0, 0, 4.0, 64.0, 8.0, 30.0, 15.0, 0.1, 0.2, 0.0,
            EntityIntegrator.STEP_PLAIN);
        return builder.build();
    }

    private static WorkBody corruptFirstRow() {
        return (batch, target, token) -> {
            long checksum = EntityIntegrator.INSTANCE.run(batch, target, token);
            target.write(0, target.posX(0) + 1.0, target.posY(0), target.posZ(0), target.yaw(0),
                target.pitch(0), target.velX(0), target.velY(0), target.velZ(0), target.flags(0));
            return checksum;
        };
    }

    private static WorkBody corruptLastRow() {
        return (batch, target, token) -> {
            long checksum = EntityIntegrator.INSTANCE.run(batch, target, token);
            int last = Math.max(0, target.filled() - 1);
            target.write(last, target.posX(last), target.posY(last) + 1.0, target.posZ(last),
                target.yaw(last), target.pitch(last), target.velX(last), target.velY(last),
                target.velZ(last), target.flags(last));
            return checksum;
        };
    }

    private static DispatchWriteBack writeBack(DispatchReadings readings) {
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        return new DispatchWriteBack(intents, (prefix, write) -> {
            String handle = prefix + ":" + handles.incrementAndGet();
            store.put(handle, write);
            return handle;
        }, store::remove, world -> 1L, readings, () -> false);
    }

    private static WorkerPool pool(DispatchReadings readings, ArenaLedger arena, int queueCap) {
        return WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY, queueCap,
            4), 1, readings, arena);
    }

    /**
     * Builds one world whose rows all fall into one region, so one batch carries all of them.
     *
     * @param entities how many rows the view carries
     * @return the view
     */
    private static EntityCandidateView view(int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, i % 4, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }
}
