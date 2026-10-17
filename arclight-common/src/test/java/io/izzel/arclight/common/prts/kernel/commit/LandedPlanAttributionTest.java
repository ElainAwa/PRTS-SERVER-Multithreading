/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.PipelineWriteClaims;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The landing report that names its plan and seat: the commit log records the explicit plan even
 * when the newest plan reused the node key, and refuses a plan that is missing, evicted, of a retired
 * lifecycle or seated elsewhere instead of falling back to the newest plan that reused the key. */
class LandedPlanAttributionTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String KEY = "chunk-pipeline/minecraft:overworld/main";
    private static final long EXPECTED_VERSION = 7L;
    private static final long DIGEST = 4242L;

    @Test
    void aReusedKeyIsRecordedUnderThePlanThatAuthorizedIt() {
        TickPlanStore store = store(4, plan(7L, 0));
        store.publish(plan(8L, 1));
        CommitLog log = log(store);
        log.beginTick(100L);

        assertEquals(8L, store.resolve(WORLD, DOMAIN, KEY).planSequence(),
            "the newest plan that reused the key is what the old lookup answers");
        assertEquals(1, store.resolveIn(8L, store.lifecycle(), WORLD, DOMAIN, KEY).position(),
            "the newest plan gives the key another seat");
        assertEquals(0, store.resolveIn(7L, store.lifecycle(), WORLD, DOMAIN, KEY).position(),
            "the explicit lookup answers the seat of the plan it was asked about");

        CommitLog.Verdict verdict = reach(log, report(7L, 0, store.lifecycle()));

        assertTrue(verdict.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(1L, log.accepted());
        CommitLog.Replay replay = log.closeTick();
        assertEquals(List.of(new CommitLog.Entry.Step(7L, 0, DIGEST)), replay.steps(),
            "the line carries the plan and the seat the report named");
        assertTrue(replay.orderMatches());
    }

    @Test
    void aMissingPlanIsRefusedInsteadOfTheNewestPlanThatCarriesTheKey() {
        TickPlanStore store = store(4, plan(8L, 0));
        CommitLog log = log(store);
        log.beginTick(1L);
        assertNotNull(store.resolve(WORLD, DOMAIN, KEY), "the newest lookup would have answered");

        CommitLog.Verdict verdict = reach(log, report(99L, 0, store.lifecycle()));

        assertRefused(log, verdict, 1L);
    }

    @Test
    void anEvictedPlanIsRefusedInsteadOfTheNewestPlanThatCarriesTheKey() {
        TickPlanStore store = new TickPlanStore(2);
        store.publish(plan(7L, 0));
        store.publish(plan(8L, 0));
        store.publish(plan(9L, 0));
        assertNull(store.planOf(7L), "the history bound evicted the plan");
        CommitLog log = log(store);
        log.beginTick(1L);
        assertEquals(9L, store.resolve(WORLD, DOMAIN, KEY).planSequence(),
            "the newest plan that reused the key is still resolvable by the old lookup");

        CommitLog.Verdict verdict = reach(log, report(7L, 0, store.lifecycle()));

        assertRefused(log, verdict, 1L);
    }

    @Test
    void aSeatTheExplicitPlanDidNotGiveIsRefused() {
        TickPlanStore store = store(4, plan(7L, 0));
        CommitLog log = log(store);
        log.beginTick(1L);
        assertEquals(0, store.resolveIn(7L, store.lifecycle(), WORLD, DOMAIN, KEY).position(),
            "the plan holds the node at another seat");

        CommitLog.Verdict verdict = reach(log, report(7L, 1, store.lifecycle()));

        assertRefused(log, verdict, 1L);
    }

    @Test
    void aPlanOfARetiredLifecycleIsRefusedEvenWhenItsSequenceIsReused() {
        TickPlanStore store = store(4, plan(7L, 0));
        long retired = store.lifecycle();
        store.reset();
        assertEquals(retired + 1L, store.lifecycle());
        store.publish(plan(7L, 0));
        CommitLog log = log(store);
        log.beginTick(1L);
        assertNotNull(store.resolveIn(7L, store.lifecycle(), WORLD, DOMAIN, KEY),
            "the reused sequence names a real seat of the new lifecycle");

        CommitLog.Verdict stale = reach(log, report(7L, 0, retired));

        assertRefused(log, stale, 1L);
        CommitLog.Verdict current = reach(log, report(7L, 0, store.lifecycle()));
        assertTrue(current.logged(), "the report of the current lifecycle still lands");
        assertEquals(1L, log.accepted());
    }

    private static void assertRefused(CommitLog log, CommitLog.Verdict verdict, long unplanned) {
        assertEquals(CommitLog.Entry.Disposition.DROPPED, verdict.disposition());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, verdict.code());
        assertEquals(0L, log.accepted());
        assertEquals(unplanned, log.unplanned());
        CommitLog.Replay replay = log.closeTick();
        for (CommitLog.Entry.Step step : replay.steps()) {
            assertEquals(0L, step.planSequence(),
                "a refused report is never attributed to a plan that reused the key");
        }
    }

    /** The report shape the claim point hands over: the plan, seat and lifecycle it saved at
     * declaration, plus the columns the commit log identifies a batch by. */
    private static PipelineWriteClaims.Report report(long planSequence, int position,
                                                     long lifecycle) {
        return new PipelineWriteClaims.Report(WORLD, DOMAIN, KEY, planSequence, position, lifecycle,
            EXPECTED_VERSION, DIGEST, 1);
    }

    /** The same call the kernel's landed-batch settlement makes: the explicit attribution goes to the
     * log, which checks it against the order source. */
    private static CommitLog.Verdict reach(CommitLog log, PipelineWriteClaims.Report report) {
        return log.reach(new CommitLog.Batch(report.worldId(), report.domainId(), report.nodeKey(),
            CommitLog.Batch.Kind.APPLY, report.expectedVersion(), report.writeSetDigest(),
            report.rows()), report.planSequence(), report.position(), report.lifecycle());
    }

    private static TickPlanStore store(int historyCap, TickPlan plan) {
        TickPlanStore store = new TickPlanStore(historyCap);
        store.publish(plan);
        return store;
    }

    /** One real plan whose commit order carries KEY at the given position. */
    private static TickPlan plan(long planSequence, int position) {
        List<TickPlan.CommitStep> order = List.of(new TickPlan.CommitStep(position, WORLD, DOMAIN,
            KEY, planSequence, 0, 1, false));
        return new TickPlan(planSequence, planSequence, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
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
                TickPlanStore.StepRef step =
                    store.resolveIn(planSequence, lifecycle, worldId, domainId, nodeKey);
                return step == null ? null
                    : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
            }
        });
    }
}
