/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A report the end drain hands over after its plan left the bounded history and its claims closed:
 * the exact retained order must still answer, otherwise every drained report is unplanned. */
class DrainRetainedOrderTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String KEY = "chunk-pipeline/minecraft:overworld/main";
    private static final long PLAN = 7L;
    private static final long EXPECTED_VERSION = 7L;
    private static final long DIGEST = 4242L;

    @Test
    void aDrainedReportUsesTheRetainedOrderAfterItsPlanLeftTheHistory() {
        TickPlanStore store = new TickPlanStore(2);
        TickPlan plan = plan(PLAN, 0);
        store.publish(plan);
        store.publish(plan(PLAN + 1L, 0));
        store.publish(plan(PLAN + 2L, 0));
        assertEquals(null, store.planOf(PLAN), "the bounded history evicted the report's plan");

        Map<Long, KernelModule.RetainedOrder> retained = new HashMap<>();
        retained.put(PLAN, new KernelModule.RetainedOrder(store.lifecycle(),
            List.copyOf(plan.commitOrder())));
        CommitLog log = new CommitLog(() -> 8, new CommitLog.OrderSource() {

            @Override
            public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                TickPlanStore.StepRef step = store.resolve(worldId, domainId, nodeKey);
                return step == null ? null
                    : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
            }

            @Override
            public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                                String domainId, String nodeKey) {
                return KernelModule.resolveRetainedOrderAt(store, retained, true, false, true,
                    planSequence, lifecycle, worldId, domainId, nodeKey);
            }
        });
        log.beginTick(1L);

        CommitLog.Verdict verdict = log.reach(new CommitLog.Batch(WORLD, DOMAIN, KEY,
            CommitLog.Batch.Kind.APPLY, EXPECTED_VERSION, DIGEST, 1), PLAN, 0, store.lifecycle());

        assertTrue(verdict.logged(), () -> "the retained order must answer the drained report");
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(1L, log.accepted());
        assertEquals(0L, log.unplanned(), "the drained report must not be an unplanned drop");
        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(PLAN, 0, DIGEST)), replay.steps());
        assertTrue(replay.orderMatches());
    }

    @Test
    void thePendingBufferFloorKeepsTheOldestBufferedPlan() {
        PipelineWriteClaims.ReportBuffer buffer = new PipelineWriteClaims.ReportBuffer(8, 1000);
        buffer.add(9L, List.of(report(9L)), null);
        buffer.add(7L, List.of(report(7L)), null);
        assertEquals(7L, buffer.pendingPlanFloor(),
            "the trim floor must see the oldest report still waiting for the end drain");

        buffer.flushAll(report -> { }, null, 45);
        assertEquals(Long.MAX_VALUE, buffer.pendingPlanFloor(),
            "an empty buffer sets no retained-order floor");
    }

    private static PipelineWriteClaims.Report report(long planSequence) {
        return new PipelineWriteClaims.Report(WORLD, DOMAIN, KEY, planSequence, 0,
            planSequence, EXPECTED_VERSION, DIGEST, 1);
    }

    private static TickPlan plan(long planSequence, int position) {
        List<TickPlan.CommitStep> order = List.of(new TickPlan.CommitStep(position, WORLD, DOMAIN,
            KEY, planSequence, 0, 1, false));
        return new TickPlan(planSequence, planSequence, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }
}
