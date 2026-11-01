/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.observe.ScaleBudgetReadings;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scale trigger is the causal half of the scale window: a real entity count over the declared
 * budget drives the one adjacent merge of the tick even when the time-share trigger did not fire,
 * the two triggers share that single merge and keep their attributions apart, and no missing,
 * stale, forged or disabled trigger can look like a scale action. */
class P2ACC08ScaleCausalityTest {

    private static final long TICK = 21L;
    private static final long EPOCH = 7L;
    private static final long WINDOW = 600L;

    @Test
    void aScaleOverrunDrivesTheMergeWithoutATimeShareTrigger() {
        ScaleBudgetReadings.reset();
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        assertEquals(2, source.taskCount());
        long count = 5L;
        long budget = 3L;
        EntityDomain.ScaleTrigger scale = EntityDomain.scaleTrigger(source, count, budget, true,
            WINDOW);
        assertNotNull(scale, "the real count is over the declared budget");
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, -1L, false, scale, 4);
        assertNotNull(merged, "the scale trigger alone asks for the merge");
        assertFalse(merged.receipts().isEmpty(), "the scale trigger really drove the merge");
        int credited = EntityDomain.markScaleMerge(scale, source, merged.plan(), merged.receipts(),
            true, WINDOW, ScaleBudgetReadings.generation(), epochs(1L));
        assertEquals(merged.receipts().size(), credited);
        assertTrue(ScaleBudgetReadings.noteScaleMerged(TICK, scale.windowStartTick(),
            scale.resetGeneration(), credited));
        ScaleBudgetReadings.noteTick(TICK, count, budget, WINDOW);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals((long) credited, ScaleBudgetReadings.degradedCount(),
            "over scale with a real merge must count the scale action");
        assertTrue(ScaleBudgetReadings.degradedCount() > 0L);
    }

    @Test
    void noTriggerAndNoWantedTokenLeavesThePlanUnmerged() {
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        assertNull(EntityDomain.mergeForTriggers(source, -1L, false, null, 4));
        assertNull(EntityDomain.mergeForTriggers(source, 0L, true, null, 4));
        assertNull(EntityDomain.mergeForTriggers(source, -1L, true, null, 4));
    }

    @Test
    void aTimeShareMergeDoesNotCreditTheScaleWindow() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, WINDOW);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        SharePlanner shares = new SharePlanner();
        OverrunRecord record = overrun(shares, "scale-a", ShareClass.ENTITY);
        long token = shares.tokenOf(record);
        assertTrue(token > 0L);
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        EntityDomain.ScaleTrigger absent = EntityDomain.scaleTrigger(source, 100L, 200L, true,
            WINDOW);
        assertNull(absent, "the real count is inside the budget, so no scale trigger exists");
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, token, true, absent, 4);
        assertNotNull(merged);
        ArrayDeque<KernelModule.PendingB3> pending = new ArrayDeque<>();
        pending.add(new KernelModule.PendingB3(token, "scale-a", ShareClass.ENTITY, TICK));
        assertTrue(KernelModule.markB3Merge(shares, actingLadder(), pending, source, merged.plan(),
            merged.receipts()));
        assertTrue(executed(shares, "scale-a", ShareClass.ENTITY));
        assertEquals(0, EntityDomain.markScaleMerge(absent, source, merged.plan(),
            merged.receipts(), true, WINDOW, ScaleBudgetReadings.generation(), epochs(1L)));
        ScaleBudgetReadings.noteTick(TICK, 100L, 200L, WINDOW);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount(),
            "a time-share action that did not come from the scale trigger is not a scale action");
    }

    @Test
    void bothTriggersShareOneMergeAndKeepTheirAttributionsApart() {
        ScaleBudgetReadings.reset();
        SharePlanner shares = new SharePlanner();
        OverrunRecord record = overrun(shares, "scale-a", ShareClass.ENTITY);
        long token = shares.tokenOf(record);
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        EntityDomain.ScaleTrigger scale = EntityDomain.scaleTrigger(source, 5L, 3L, true, WINDOW);
        assertNotNull(scale);
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, token, true, scale, 4);
        assertNotNull(merged);
        int steps = merged.receipts().size();
        assertTrue(steps > 0);
        assertEquals(source.taskCount() - merged.plan().taskCount(), steps,
            "the two triggers asked for one merge, not two");
        ArrayDeque<KernelModule.PendingB3> pending = new ArrayDeque<>();
        pending.add(new KernelModule.PendingB3(token, "scale-a", ShareClass.ENTITY, TICK));
        assertTrue(KernelModule.markB3Merge(shares, actingLadder(), pending, source, merged.plan(),
            merged.receipts()));
        assertTrue(executed(shares, "scale-a", ShareClass.ENTITY));
        int credited = EntityDomain.markScaleMerge(scale, source, merged.plan(),
            merged.receipts(), true, WINDOW, ScaleBudgetReadings.generation(), epochs(1L));
        assertEquals(steps, credited, "the same single merge is the one scale step");
        assertTrue(ScaleBudgetReadings.noteScaleMerged(TICK, scale.windowStartTick(),
            scale.resetGeneration(), credited));
        ScaleBudgetReadings.noteTick(TICK, 5L, 3L, WINDOW);
        assertEquals((long) credited, ScaleBudgetReadings.degradedCount());
        assertFalse(KernelModule.markB3Merge(shares, actingLadder(), pending, source, merged.plan(),
            merged.receipts()), "a replay marks no time-share record again");
        assertFalse(ScaleBudgetReadings.noteScaleMerged(TICK, scale.windowStartTick(),
            scale.resetGeneration(), credited), "a replay queues no scale action again");
        assertEquals((long) credited, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void aScaleTriggerWithoutARealMergeDoesNotCreditTheWindow() {
        ScaleBudgetReadings.reset();
        WorkPlan source = plan(view("scale-a", 1L, 2, 0));
        assertEquals(1, source.taskCount());
        EntityDomain.ScaleTrigger scale = EntityDomain.scaleTrigger(source, 5L, 3L, true, WINDOW);
        assertNotNull(scale);
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, -1L, false, scale, 4);
        assertNotNull(merged);
        assertTrue(merged.receipts().isEmpty(), "a single task has nothing to merge");
        assertEquals(0, EntityDomain.markScaleMerge(scale, source, merged.plan(),
            merged.receipts(), true, WINDOW, ScaleBudgetReadings.generation(), epochs(1L)));
        ScaleBudgetReadings.noteTick(TICK, 5L, 3L, WINDOW);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount(),
            "no merge happened, so no scale action is counted");
    }

    @Test
    void aScaleTriggerNeedsTheSwitchTheBudgetAndAPlanToMerge() {
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        assertNull(EntityDomain.scaleTrigger(source, 5L, 3L, false, WINDOW),
            "the action switch is off");
        assertNull(EntityDomain.scaleTrigger(source, 3L, 3L, true, WINDOW),
            "the count is not over the budget");
        assertNull(EntityDomain.scaleTrigger(source, 2L, 3L, true, WINDOW));
        assertNull(EntityDomain.scaleTrigger(source, 5L, 0L, true, WINDOW),
            "a zero budget cannot be overrun");
        WorkPlan empty = WorkPlan.freeze(TICK, EPOCH, List.of(), 1, 1L);
        assertNull(EntityDomain.scaleTrigger(empty, 5L, 3L, true, WINDOW),
            "no task means no merge to drive");
    }

    @Test
    void aScaleTriggerOfAnotherIdentityIsRejected() {
        ScaleBudgetReadings.reset();
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        EntityDomain.ScaleTrigger valid = EntityDomain.scaleTrigger(source, 5L, 3L, true, WINDOW);
        assertNotNull(valid);
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, -1L, false, valid, 4);
        long generation = ScaleBudgetReadings.generation();
        assertEquals(merged.receipts().size(), mark(source, merged, generation, epochs(1L), valid),
            "the control trigger is admitted");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK + 1L, EPOCH,
            valid.worlds(), valid.worldEpochs(), 5L, 3L, valid.windowStartTick(),
            valid.resetGeneration(), true), "another tick");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH + 1L,
            valid.worlds(), valid.worldEpochs(), 5L, 3L, valid.windowStartTick(),
            valid.resetGeneration(), true), "another plan epoch");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH,
            List.of("scale-b"), List.of(1L), 5L, 3L, valid.windowStartTick(),
            valid.resetGeneration(), true), "another world set");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH,
            valid.worlds(), valid.worldEpochs(), 3L, 3L, valid.windowStartTick(),
            valid.resetGeneration(), true), "a count inside the budget");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH,
            valid.worlds(), valid.worldEpochs(), 5L, 3L, valid.windowStartTick() + 1L,
            valid.resetGeneration(), true), "another window");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH,
            valid.worlds(), valid.worldEpochs(), 5L, 3L, valid.windowStartTick(),
            valid.resetGeneration(), false), "the trigger saw the switch off");
        assertRejected(source, merged, generation, epochs(1L), copy(valid, TICK, EPOCH,
            valid.worlds(), valid.worldEpochs(), 5L, 3L, valid.windowStartTick(),
            valid.resetGeneration() + 1L, true), "another reset generation");
        assertRejected(source, merged, generation + 1L, epochs(1L), valid,
            "a reset generation that no longer matches");
        assertRejected(source, merged, generation, epochs(2L), valid,
            "a world whose lifecycle epoch moved");
        assertEquals(0, EntityDomain.markScaleMerge(valid, source, merged.plan(),
            merged.receipts(), false, WINDOW, generation, epochs(1L)),
            "the action switch is off");
        assertEquals(0, EntityDomain.markScaleMerge(valid, source, merged.plan(), List.of(),
            true, WINDOW, generation, epochs(1L)), "no receipt proves a merge");
        assertEquals(0, EntityDomain.markScaleMerge(valid, source, source, merged.receipts(), true,
            WINDOW, generation, epochs(1L)),
            "the merged plan is the source plan, not a merge");
    }

    @Test
    void aForgedReceiptOfADifferentMergeIsRejected() {
        ScaleBudgetReadings.reset();
        WorkPlan source = plan(view("scale-a", 1L, 2, 0, 1));
        EntityDomain.ScaleTrigger valid = EntityDomain.scaleTrigger(source, 5L, 3L, true, WINDOW);
        assertNotNull(valid);
        WorkPlan.MergeResult merged = EntityDomain.mergeForTriggers(source, -1L, false, valid, 4);
        long generation = ScaleBudgetReadings.generation();
        WorkPlan.MergeReceipt real = merged.receipts().get(0);
        WorkPlan.MergeReceipt forged = new WorkPlan.MergeReceipt(real.worldId(),
            real.mergedTasks(), real.entities(), real.firstBatchId(), real.lastBatchId() + 100L,
            real.sourcePlanTick(), real.sourcePlanEpoch());
        assertEquals(0, EntityDomain.markScaleMerge(valid, source, merged.plan(), List.of(forged),
            true, WINDOW, generation, epochs(1L)), "a batch that is not in the source plan");
        WorkPlan.MergeReceipt otherWorld = new WorkPlan.MergeReceipt("scale-b",
            real.mergedTasks(), real.entities(), real.firstBatchId(), real.lastBatchId(),
            real.sourcePlanTick(), real.sourcePlanEpoch());
        assertEquals(0, EntityDomain.markScaleMerge(valid, source, merged.plan(),
            List.of(otherWorld), true, WINDOW, generation, epochs(1L)), "a world outside the scope");
    }

    private static WorkPlan plan(EntityCandidateView... views) {
        return WorkPlan.freeze(TICK, EPOCH, List.of(views), 1, 1L);
    }

    private static EntityCandidateView view(String worldId, long worldEpoch, int perRegion,
                                            int... regions) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, worldEpoch);
        long seq = 1L;
        for (int region : regions) {
            for (int index = 0; index < perRegion; index++) {
                builder.add(seq++, region, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
            }
        }
        return builder.build();
    }

    private static OverrunRecord overrun(SharePlanner shares, String worldId,
                                         ShareClass shareClass) {
        OverrunRecord record = shares.record(new ShareTable.ShareRow(worldId, shareClass, 1.0, 2.0),
            "runtime", TICK);
        shares.recordWouldDegrade(record);
        return record;
    }

    private static DegradeLadder actingLadder() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B3, TICK);
        return ladder;
    }

    private static boolean executed(SharePlanner shares, String worldId, ShareClass shareClass) {
        boolean found = false;
        boolean executed = false;
        for (OverrunRecord stored : shares.records()) {
            if (stored.tickIndex() == TICK && stored.worldId().equals(worldId)
                && stored.overClass() == shareClass) {
                found = true;
                executed = stored.actionExecuted();
            }
        }
        assertTrue(found, "the trigger record is stored");
        return executed;
    }

    private static ToLongFunction<String> epochs(long epoch) {
        return worldId -> epoch;
    }

    private static int mark(WorkPlan source, WorkPlan.MergeResult merged, long generation,
                            ToLongFunction<String> worldEpoch, EntityDomain.ScaleTrigger trigger) {
        return EntityDomain.markScaleMerge(trigger, source, merged.plan(), merged.receipts(), true,
            WINDOW, generation, worldEpoch);
    }

    private static void assertRejected(WorkPlan source, WorkPlan.MergeResult merged, long generation,
                                       ToLongFunction<String> worldEpoch,
                                       EntityDomain.ScaleTrigger trigger, String what) {
        assertEquals(0, mark(source, merged, generation, worldEpoch, trigger),
            "a scale trigger of " + what + " is not admitted");
    }

    private static EntityDomain.ScaleTrigger copy(EntityDomain.ScaleTrigger real, long tickIndex,
                                                  long planEpoch, List<String> worlds,
                                                  List<Long> worldEpochs, long entityCount,
                                                  long budget, long windowStartTick,
                                                  long resetGeneration, boolean actionsEnabled) {
        return new EntityDomain.ScaleTrigger(tickIndex, planEpoch, worlds, worldEpochs, entityCount,
            budget, windowStartTick, resetGeneration, actionsEnabled);
    }
}
