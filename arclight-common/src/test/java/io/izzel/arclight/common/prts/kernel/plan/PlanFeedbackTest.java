/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loop the planning period closes on itself: the frame of one tick becomes an input of the
 * next plan, and a frame taken over a window is refused instead of consumed. */
class PlanFeedbackTest {

    private static final String WORLD = "world-a";

    private static TickPlanPlanner.Input input(TickPlanStore.Feedback feedback) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        List<JobDeclaration> declarations = List.of(new JobDeclaration("a/0", 1L, WORLD, "entity", 0,
            List.of(), 0, "a", "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4));
        return new TickPlanPlanner.Input(10L, 2L, 1L, List.of(WORLD), List.of("entity"),
            TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"), declarations,
            new SharePlanner().plan(List.of(WORLD), 10L, Map.of()), 64, feedback);
    }

    @Test
    void aRefusedPlanOfTheTickBeforeHoldsTheNextPairConservative() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(
            input(TickPlanStore.Feedback.tick(9L, 1L, 1L, 0L, 3L, 0L, 0.25)));

        assertTrue(result.ok(), () -> "refused: " + result.code());
        TickPlan.DomainMode mode = result.plan().modeOf(WORLD, "entity");
        assertEquals(TickPlan.Mode.CONSERVATIVE, mode.mode());
        assertEquals(TickPlan.Reason.DEGRADED, mode.reason());
        assertEquals(0.25, result.plan().feedback().failureRate());
        assertEquals(9L, result.plan().feedback().tickIndex());
        assertEquals(1L, result.plan().tickIndex() - result.plan().feedback().tickIndex());
    }

    @Test
    void anUnknownSiteOfTheTickBeforeIsCarriedForOneTick() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(
            input(TickPlanStore.Feedback.tick(9L, 1L, 0L, 2L, 0L, 2L, 0.0)));

        assertTrue(result.ok());
        assertEquals(TickPlan.Reason.UNKNOWN_SITE, result.plan().modeOf(WORLD, "entity").reason());
    }

    @Test
    void aFrameWithoutFeedbackLeavesThePlanAsItWas() {
        TickPlanPlanner.Result plain = TickPlanPlanner.plan(input(TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result taken = TickPlanPlanner.plan(
            input(TickPlanStore.Feedback.tick(9L, 1L, 0L, 0L, 0L, 0L, 0.0)));

        assertTrue(plain.ok() && taken.ok());
        assertEquals(TickPlan.Mode.ACTIVE, plain.plan().modeOf(WORLD, "entity").mode());
        assertEquals(TickPlan.Mode.ACTIVE, taken.plan().modeOf(WORLD, "entity").mode());
        assertNotEquals(plain.plan().contentHash(), taken.plan().contentHash());
        assertFalse(TickPlanStore.Feedback.none().observed());
        assertTrue(taken.plan().feedback().observed());
    }

    @Test
    void aWindowScopedFrameIsRefusedInsteadOfConsumed() {
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(
            input(TickPlanStore.Feedback.overWindow(9L, 600L, 0.25)));

        assertEquals(RejectCode.COUNTER_MISSING, refused.code());
        assertEquals(null, refused.plan());
    }

    @Test
    void theStoreTakesTheFrameOfOneTickAndNamesTheTickItWasTakenOn() {
        TickPlanStore store = new TickPlanStore(4);
        store.noteControl(TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"));
        store.noteFailure(RejectCode.DAG_CYCLE);
        store.takeFeedback(9L, 1L);

        TickPlanStore.Feedback taken = store.feedback();

        assertTrue(taken.observed());
        assertTrue(taken.tickScoped());
        assertEquals(9L, taken.tickIndex());
        assertEquals(1L, taken.tickFailures());
        assertEquals(1L, taken.totalFailures());
        assertNotNull(store.feedback());
    }
}
