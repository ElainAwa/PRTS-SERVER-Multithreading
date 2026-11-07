/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A landed entity batch is reported under the exact seat of the plan named for it: the newest plan
 * that reused its node key is never consulted, a batch the named plan does not seat is written as an
 * unplanned line instead of being judged against another plan's place, and every landed batch keeps
 * its own line. */
class EntityLandedCommitSeatTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = "entity";
    private static final String KEY = "entity/minecraft:overworld/r0.0";
    private static final String ABSENT = "entity/minecraft:overworld/r9.9";
    private static final long DIGEST = 4242L;

    @Test
    void theExactPlanSeatIsReportedNotTheNewestPlanThatReusedTheKey() {
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan(1L, 1));
        store.publish(plan(2L, 7));
        CommitLog log = log(store);
        log.beginTick(1L);

        CommitLog.Verdict verdict = KernelModule.reachLandedEntityBatch(log, store, 1L,
            store.lifecycle(), batch(KEY));

        assertNotNull(verdict);
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(1L, verdict.sequence());
        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(1L, 1, DIGEST)), replay.steps(),
            "the line carries the named plan's seat, not the newest plan that reused the key");
        assertEquals(0L, log.orderViolations());
        assertEquals(0L, log.unplanned());
    }

    @Test
    void aKeyTheNamedPlanDoesNotSeatIsUnplannedNotAnOrderViolation() {
        TickPlanStore store = new TickPlanStore(8);
        store.publish(planSeatingAbsent(1L, 1));
        store.publish(plan(2L, 0));

        CommitLog plain = log(store);
        plain.beginTick(1L);
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED,
            plain.reach(batch(KEY), 2L, 0, store.lifecycle()).disposition());
        CommitLog.Verdict resolvedByNewest = plain.reach(batch(ABSENT));
        // The old path resolves the key to the older plan that carries it and drops it as an order
        // violation, which is the trajectory the exact entry removes.
        assertEquals(CommitLog.Entry.Disposition.DROPPED, resolvedByNewest.disposition());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, resolvedByNewest.code());
        assertEquals(1L, plain.orderViolations());
        assertEquals(0L, plain.unplanned());

        CommitLog exact = log(store);
        exact.beginTick(1L);
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED,
            exact.reach(batch(KEY), 2L, 0, store.lifecycle()).disposition());
        CommitLog.Verdict unplanned = KernelModule.reachLandedEntityBatch(exact, store, 2L,
            store.lifecycle(), batch(ABSENT));
        assertNotNull(unplanned, "an unattributable batch is still written, as unplanned");
        assertEquals(CommitLog.Entry.Disposition.DROPPED, unplanned.disposition());
        assertEquals(-1, unplanned.position(), "the line claims no planned place");
        assertEquals(2L, exact.sequence(), "the accepted line and the unplanned line are both written");
        assertEquals(1L, exact.unplanned());
        assertEquals(1L, exact.dropped());
        assertEquals(0L, exact.orderViolations(), "the newest plan's place is never judged");
        assertTrue(exact.closeTick().orderMatches());
    }

    @Test
    void everyLandedBatchOfOneSeatIsStillReported() {
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan(1L, 1));
        CommitLog log = log(store);
        log.beginTick(1L);

        CommitLog.Verdict first = KernelModule.reachLandedEntityBatch(log, store, 1L,
            store.lifecycle(), batch(KEY));
        assertNotNull(first);
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, first.disposition());

        CommitLog.Verdict second = KernelModule.reachLandedEntityBatch(log, store, 1L,
            store.lifecycle(), batch(KEY, 99L));
        assertNotNull(second, "a report is never suppressed: every landed batch is observed");
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, second.disposition());
        assertEquals(2L, second.sequence());

        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(1L, 1, DIGEST),
            new CommitLog.Entry.Step(1L, 1, 99L)), replay.steps(),
            "each report keeps its own line and its own write set");
        assertEquals(2L, log.sequence());
        assertEquals(2L, log.accepted());
        assertEquals(0L, log.dropped());
        assertEquals(0L, log.orderViolations(), "an equal seat is not an order violation");
    }

    @Test
    void differentSeatsAreEachReported() {
        TickPlanStore store = new TickPlanStore(8);
        store.publish(planSeatingAbsent(1L, 1));
        CommitLog log = log(store);
        log.beginTick(1L);

        assertNotNull(KernelModule.reachLandedEntityBatch(log, store, 1L, store.lifecycle(),
            batch(KEY)));
        assertNotNull(KernelModule.reachLandedEntityBatch(log, store, 1L, store.lifecycle(),
            batch(ABSENT)));

        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(1L, 1, DIGEST),
            new CommitLog.Entry.Step(1L, 2, DIGEST)), replay.steps(),
            "each planned seat is its own line");
        assertEquals(2L, log.accepted());
        assertEquals(0L, log.dropped());
        assertEquals(0L, log.orderViolations());
        assertTrue(replay.orderMatches());
    }

    @Test
    void aNewPlanReportsItsOwnSeatOfTheSameKey() {
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan(1L, 1));
        CommitLog log = log(store);
        log.beginTick(1L);

        assertNotNull(KernelModule.reachLandedEntityBatch(log, store, 1L, store.lifecycle(),
            batch(KEY)));
        store.publish(plan(2L, 1));
        assertNotNull(KernelModule.reachLandedEntityBatch(log, store, 2L, store.lifecycle(),
            batch(KEY)), "the new plan owns the seat, so its report is reached");

        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(1L, 1, DIGEST),
            new CommitLog.Entry.Step(2L, 1, DIGEST)), replay.steps());
        assertEquals(2L, log.accepted());
        assertEquals(0L, log.orderViolations());
    }

    private static CommitLog log(TickPlanStore store) {
        return new CommitLog(() -> 8, new CommitLog.OrderSource() {

            @Override
            public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                TickPlanStore.StepRef step = store.resolve(worldId, domainId, nodeKey);
                return step == null ? null
                    : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
            }

            @Override
            public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                                String domainId, String nodeKey) {
                return KernelModule.resolveRetainedOrderAt(store, Map.of(), false, false, false,
                    planSequence, lifecycle, worldId, domainId, nodeKey);
            }
        });
    }

    private static TickPlan plan(long planSequence, int position) {
        List<TickPlan.CommitStep> order = List.of(new TickPlan.CommitStep(position, WORLD, DOMAIN,
            KEY, planSequence, 0, 1, false));
        return new TickPlan(planSequence, planSequence, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    /** A plan that seats the key this test drives and the key no later plan carries. */
    private static TickPlan planSeatingAbsent(long planSequence, int position) {
        List<TickPlan.CommitStep> order = new ArrayList<>();
        order.add(new TickPlan.CommitStep(position, WORLD, DOMAIN, KEY, planSequence, 0, 1, false));
        order.add(new TickPlan.CommitStep(position + 1, WORLD, DOMAIN, ABSENT, planSequence, 0, 1,
            false));
        return new TickPlan(planSequence, planSequence, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    private static CommitLog.Batch batch(String nodeKey) {
        return batch(nodeKey, DIGEST);
    }

    private static CommitLog.Batch batch(String nodeKey, long digest) {
        return new CommitLog.Batch(WORLD, DOMAIN, nodeKey, CommitLog.Batch.Kind.APPLY, 7L, digest, 2);
    }
}
