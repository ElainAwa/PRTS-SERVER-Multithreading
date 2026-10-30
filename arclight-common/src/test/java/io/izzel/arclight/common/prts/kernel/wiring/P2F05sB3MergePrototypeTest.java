/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline prototype of the B3 entity-scope merge: adjacent frozen tasks merge without moving a
 * row, the plan shape changes, the cap holds, and the untriggered plan stays untouched. */
class P2F05sB3MergePrototypeTest {

    @Test
    void adjacentEntityTasksMergeWithoutChangingRowsOrOrder() {
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(view(4)), 1, 1L);
        assertEquals(4, plan.taskCount());
        List<WorkTask> before = plan.tasks();

        WorkPlan.MergeResult result = plan.mergeAdjacent(2);
        assertEquals(2, result.plan().taskCount());
        assertEquals(2, result.receipts().size());
        assertEquals(before.size(), result.plan().taskCount() + result.receipts().size());
        assertEquals(4, result.plan().tasks().stream().mapToInt(WorkTask::entityCount).sum());
        for (WorkPlan.MergeReceipt receipt : result.receipts()) {
            assertEquals("world", receipt.worldId());
            assertTrue(receipt.entities() <= 2);
            assertTrue(receipt.mergedTasks() >= 2);
        }

        int expected = 0;
        for (WorkTask task : result.plan().tasks()) {
            assertEquals(expected, task.entitySeqStart());
            expected = task.entitySeqEnd();
        }
        assertEquals(4, expected);
        assertEquals(plan.views(), result.plan().views());
    }

    @Test
    void dispatchReceivesTheMergedEntityBatches() {
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(view(4)), 1, 1L);
        WorkPlan merged = plan.mergeAdjacent(2).plan();
        DispatchPass pass = DispatchPass.serialFallback(merged, new DispatchReadings(),
            new TaskLedger(1L));
        assertEquals(merged.taskCount(), pass.entries().size());
        int rows = 0;
        for (DispatchPass.Entry entry : pass.entries()) {
            assertEquals(entry.batch().task().batchId(), entry.batch().batchId());
            rows += entry.batch().rangeEnd() - entry.batch().rangeStart();
        }
        assertEquals(4, rows);
    }

    @Test
    void mergedTaskCarriesTheShareSumAndComposedScopes() {
        EntityCandidateView view = view(2);
        WorkPlan.WorkTask first = new WorkPlan.WorkTask(1L, "world", "r1", 1L, 0, 1, 1L, 1L,
            10, "scope-a");
        WorkPlan.WorkTask second = new WorkPlan.WorkTask(2L, "world", "r2", 2L, 1, 2, 1L, 1L,
            5, "scope-b");
        WorkPlan plan = new WorkPlan(1L, 1L, List.of(view), List.of(first, second), 2L);
        WorkPlan.MergeResult merged = plan.mergeAdjacent(10);
        assertEquals(1, merged.plan().taskCount());
        WorkPlan.WorkTask task = merged.plan().tasks().get(0);
        assertEquals(15, task.shareMsHint());
        assertEquals("r1+r2", task.regionId());
        assertEquals("scope-a+scope-b", task.cancelScope());
    }

    @Test
    void multipleTokensMarkIndependentlyAndFailuresStayFalse() {
        SharePlanner planner = new SharePlanner();
        ShareTable.ShareRow first = new ShareTable.ShareRow("world", ShareClass.ENTITY, 1.0, 2.0);
        ShareTable.ShareRow second = new ShareTable.ShareRow("world", ShareClass.ENTITY, 1.0, 2.0);
        OverrunRecord recordA = planner.record(first, "runtime", 1L);
        OverrunRecord recordB = planner.record(second, "runtime", 1L);
        planner.recordWouldDegrade(recordA);
        planner.recordWouldDegrade(recordB);
        long tokenA = planner.tokenOf(recordA);
        long tokenB = planner.tokenOf(recordB);
        assertTrue(tokenA > 0L && tokenB > 0L && tokenA != tokenB);
        assertTrue(planner.markTokenExecuted(tokenA));
        assertFalse(planner.markTokenExecuted(tokenA));
        assertTrue(planner.markTokenExecuted(tokenB));
        assertFalse(planner.markTokenExecuted(99999L));
        planner.clearRecordTokens();
        assertFalse(planner.hasToken(tokenA));
    }

    @Test
    void capAndRecoveryKeepTheNormalPlan() {
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(view(6)), 1, 1L);
        WorkPlan.MergeResult capped = plan.mergeAdjacent(2);
        for (WorkTask task : capped.plan().tasks()) {
            assertTrue(task.entityCount() <= 2, "merged task above the cap");
        }
        assertEquals(6, plan.tasks().stream().mapToInt(WorkTask::entityCount).sum());
        assertEquals(6, plan.taskCount());
    }

    @Test
    void tokenMarksOnlyTheTriggerRecord() {
        SharePlanner planner = new SharePlanner();
        ShareTable.ShareRow over = new ShareTable.ShareRow("world", ShareClass.ENTITY, 1.0, 2.0);
        OverrunRecord record = planner.record(over, "runtime", 1L);
        long token = planner.tokenOf(record);
        assertTrue(token > 0L);
        planner.recordWouldDegrade(record);
        assertFalse(planner.anyActionExecuted());
        assertTrue(planner.markTokenExecuted(token));
        assertTrue(planner.anyActionExecuted());
        assertFalse(planner.markTokenExecuted(token));
        assertEquals(DegradeLevel.B3, record.wouldDegradeLevel());
    }

    @Test
    void twoLargeRegionsMergeOnlyWithTheLargestTaskBound() {
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(viewRegions(1024, 0, 4)), 4, 1L);
        assertEquals(2, plan.taskCount());
        assertEquals(1024, plan.tasks().get(0).entityCount());
        assertTrue(plan.mergeAdjacent(8).receipts().isEmpty());

        WorkPlan.MergeResult merged = plan.mergeAdjacent(2048);
        assertEquals(1, merged.receipts().size());
        assertEquals(1, merged.plan().taskCount());
        assertEquals(2048, merged.plan().tasks().get(0).entityCount());
    }

    private static EntityCandidateView view(int count) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("world", 1L);
        for (int index = 0; index < count; index++) {
            builder.add(index + 1L, index, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        }
        return builder.build();
    }

    private static EntityCandidateView viewRegions(int perRegion, int... chunkXs) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("world", 1L);
        long seq = 1L;
        for (int chunkX : chunkXs) {
            for (int index = 0; index < perRegion; index++) {
                builder.add(seq++, chunkX, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
            }
        }
        return builder.build();
    }
}
