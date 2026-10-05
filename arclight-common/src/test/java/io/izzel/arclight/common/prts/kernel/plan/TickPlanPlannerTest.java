/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frozen plan of one tick: its shape, the modes it decides, the refusals of the planning period
 * and the store that publishes it. */
class TickPlanPlannerTest {

    private static final String WORLD = "world-a";

    private static JobDeclaration declaration(String key, JobDeclaration.SiteClass siteClass) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, List.of(), 0, key,
            "region", List.of(ref), List.of(ref), ShareClass.ENTITY, siteClass, 0, 4);
    }

    private static List<JobDeclaration> declarations(JobDeclaration.SiteClass siteClass) {
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", siteClass));
        declarations.add(declaration("a/1", siteClass));
        return declarations;
    }

    private static ShareTable table(long tick) {
        return new SharePlanner().plan(List.of(WORLD), tick, Map.of());
    }

    private static TickPlanStore.Control control() {
        return TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal");
    }

    private static TickPlanPlanner.Input input(JobDeclaration.SiteClass siteClass, long sequence) {
        return new TickPlanPlanner.Input(10L, sequence, 1L, List.of(WORLD), List.of("entity"),
            control(), declarations(siteClass), table(10L), 64, TickPlanStore.Feedback.none());
    }

    @Test
    void thePlanFreezesTheGraphTheOrdersTheTableAndTheModes() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.PARALLEL,
            2L));

        assertTrue(result.ok(), () -> "refused: " + result.code());
        TickPlan plan = result.plan();
        assertEquals(10L, plan.tickIndex());
        assertEquals(2L, plan.planSequence());
        assertEquals(2, plan.graph().nodeCount());
        assertEquals(plan.graph().order(), plan.topologicalOrder());
        assertEquals(2, plan.commitOrder().size());
        assertEquals(0, plan.commitOrder().get(0).position());
        assertEquals(1, plan.commitOrder().get(1).position());
        assertEquals(WORLD, plan.commitOrder().get(0).worldId());
        assertEquals("entity", plan.commitOrder().get(0).domainId());
        assertEquals(ShareClass.rowCount(), plan.shareTable().rows().size());
        assertEquals(1, plan.domainModes().size());
        assertEquals(TickPlan.Mode.ACTIVE, plan.modeOf(WORLD, "entity").mode());
        assertEquals(TickPlan.Reason.NONE, plan.modeOf(WORLD, "entity").reason());
        assertEquals(0, plan.unknownSites());
    }

    @Test
    void anUnknownSiteIsForcedOntoTheConservativeModeAndCounted() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.UNKNOWN,
            2L));

        assertTrue(result.ok());
        assertEquals(2, result.plan().unknownSites());
        TickPlan.DomainMode mode = result.plan().modeOf(WORLD, "entity");
        assertEquals(TickPlan.Mode.CONSERVATIVE, mode.mode());
        assertEquals(TickPlan.Reason.UNKNOWN_SITE, mode.reason());
        assertEquals(1, result.plan().domainModes().size());
    }

    @Test
    void aWorldWithoutDeclarationsIsDeferred() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of(WORLD), List.of("entity"), control(), List.of(), table(10L), 64,
            TickPlanStore.Feedback.none()));

        assertTrue(result.ok());
        // An empty job set still freezes a plan: the modes of the pairs nobody declared are part of
        // what the tick decided.
        assertEquals(0, result.plan().graph().nodeCount());
        assertEquals(0, result.plan().commitOrder().size());
        TickPlan.DomainMode mode = result.plan().modeOf(WORLD, "entity");
        assertEquals(TickPlan.Mode.DEFERRED, mode.mode());
        assertEquals(TickPlan.Reason.NO_DECLARATION, mode.reason());
    }

    @Test
    void theSameInputFoldsToTheSamePlan() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.PARALLEL,
            2L));
        TickPlanPlanner.Result second = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.PARALLEL,
            2L));
        TickPlanPlanner.Result later = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.PARALLEL,
            3L));

        assertTrue(first.ok() && second.ok() && later.ok());
        assertEquals(first.plan().contentHash(), second.plan().contentHash());
        assertFalse(first.plan().contentHash() == later.plan().contentHash());
    }

    @Test
    void anInputThePlanningPeriodMustRefuseIsNotPlanned() {
        TickPlanPlanner.Input base = input(JobDeclaration.SiteClass.PARALLEL, 2L);
        TickPlanPlanner.Result noControl = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of(WORLD), List.of("entity"), TickPlanStore.Control.missing(), base.declarations(),
            base.shareTable(), 64, TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result noTable = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of(WORLD), List.of("entity"), control(), base.declarations(), null, 64,
            TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result rolledBack = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 0L,
            List.of(WORLD), List.of("entity"), control(), base.declarations(), base.shareTable(),
            64, TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result regressed = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 1L, 1L,
            List.of(WORLD), List.of("entity"), control(), base.declarations(), base.shareTable(),
            64, TickPlanStore.Feedback.none()));

        assertEquals(RejectCode.COUNTER_MISSING, noControl.code());
        assertEquals(RejectCode.COUNTER_MISSING, noTable.code());
        assertEquals(RejectCode.WORLD_LIFECYCLE_DENIED, rolledBack.code());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, regressed.code());
        assertNull(noControl.plan());
    }

    @Test
    void aSplitJobReceivesAnIntentStepAfterItsOwnStep() {
        JobDeclaration.DomainRef entity = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        JobDeclaration.DomainRef lighting = new JobDeclaration.DomainRef(WORLD, "light", 0);
        JobDeclaration split = new JobDeclaration("a/0", 1L, WORLD, "entity", 0, List.of(), 0, "r",
            "region", List.of(entity, lighting), List.of(entity, lighting), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4);
        TickPlanPlanner.Result result = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of(WORLD), List.of("entity"), control(), List.of(split), table(10L), 64,
            TickPlanStore.Feedback.none()));

        assertTrue(result.ok());
        assertEquals(2, result.plan().commitOrder().size());
        assertEquals(1, result.plan().splitIntents());
        assertFalse(result.plan().commitOrder().get(0).intent());
        assertTrue(result.plan().commitOrder().get(1).intent());
        assertEquals("intent", result.plan().commitOrder().get(1).domainId());
    }

    @Test
    void theStorePublishesThePlanTheControlAndTheResolvedOrder() {
        TickPlanStore store = new TickPlanStore(2);
        store.noteControl(TickPlanStore.Control.missing());
        assertFalse(store.control().observed());
        TickPlanPlanner.Result result = TickPlanPlanner.plan(input(JobDeclaration.SiteClass.PARALLEL,
            2L));
        assertTrue(result.ok());
        store.publish(result.plan());
        store.noteControl(TickPlanStore.Control.of(10L, 2L, 1L, 0.5, 0L, 0L, 3.0, "normal"));

        assertNotNull(store.latest());
        assertEquals(1L, store.plansBuilt());
        assertEquals(1, store.historySize());
        assertTrue(store.control().observed());
        assertEquals(2L, store.control().planSequence());
        TickPlanStore.StepRef ref = store.resolve(WORLD, "entity", "a/0");
        assertNotNull(ref);
        assertEquals(2L, ref.planSequence());
        assertEquals(0, ref.position());
        assertFalse(ref.intent());
        assertNull(store.resolve(WORLD, "entity", "nobody"));
        assertEquals(1L, store.unresolvedLookups());

        store.noteFailure(RejectCode.DAG_CYCLE);
        assertEquals(1L, store.failures());
        assertEquals(RejectCode.DAG_CYCLE, store.lastFailure());
        assertEquals(0.5, store.failureRate(), 1.0e-9);
    }
}
