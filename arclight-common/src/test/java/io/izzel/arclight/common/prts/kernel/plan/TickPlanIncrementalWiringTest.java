/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planning period goes through the bounded recompute: the first plan is a budgeted bootstrap,
 * an unchanged declaration set reuses the previous graph, a changed set is a full fallback inside its
 * explicit budget, and a graph too large for the budget is refused without publishing a partial plan.
 * The covered path performs no full recompute without a budget; that count is a test-only diagnostic
 * and is not added as a readout key. */
class TickPlanIncrementalWiringTest {

    private static final String WORLD = "world-a";
    private static final int CAP = 64;

    private static JobDeclaration declaration(String key, int priority,
                                              List<String> predecessors) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, predecessors, priority,
            key, "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
    }

    private static List<JobDeclaration> chain() {
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", 0, List.of()));
        declarations.add(declaration("a/1", 1, List.of("a/0")));
        declarations.add(declaration("a/2", 2, List.of("a/1")));
        return declarations;
    }

    /** The whole graph of one queue bound: every declaration names all earlier ones as predecessors. */
    private static List<JobDeclaration> dense(int count) {
        List<JobDeclaration> declarations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<String> predecessors = new ArrayList<>();
            for (int earlier = 0; earlier < index; earlier++) {
                predecessors.add("a/" + earlier);
            }
            declarations.add(declaration("a/" + index, 0, predecessors));
        }
        return declarations;
    }

    private static ShareTable table() {
        return new SharePlanner().plan(List.of(WORLD), 10L, Map.of());
    }

    private static TickPlanStore.Control control() {
        return TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal");
    }

    private static TickPlanPlanner.Input input(long sequence, List<JobDeclaration> declarations,
                                               JobGraph previous) {
        return new TickPlanPlanner.Input(10L, sequence, 1L, List.of(WORLD), List.of("entity"),
            control(), declarations, table(), CAP, TickPlanStore.Feedback.none(), previous);
    }

    @Test
    void theFirstPlanIsABudgetedBootstrap() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(input(2L, chain(), null));

        assertTrue(result.ok(), () -> "refused: " + result.code() + " " + result.item());
        JobGraphBuilder.Recompute recompute = result.recompute();
        assertNotNull(recompute);
        assertEquals(JobGraphBuilder.RecomputeMode.BOOTSTRAP, recompute.mode());
        assertTrue(recompute.budgeted());
        assertEquals(JobGraphBuilder.scanWorkBudget(CAP), recompute.scanBudget());
        assertEquals(JobGraphBuilder.fullWorkBudget(CAP), recompute.fullBudget());
        assertTrue(recompute.fullWork() > 0);
        assertTrue(recompute.fullWork() <= recompute.fullBudget());
    }

    @Test
    void unchangedDeclarationsReuseThePreviousGraphAndFoldToTheSamePlan() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, chain(), null));
        assertTrue(first.ok());
        TickPlanPlanner.Result reused = TickPlanPlanner.plan(
            input(3L, chain(), first.plan().graph()));
        TickPlanPlanner.Result full = TickPlanPlanner.plan(input(3L, chain(), null));

        assertTrue(reused.ok(), () -> "refused: " + reused.code());
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, reused.recompute().mode());
        assertEquals(0, reused.recompute().fullWork());
        assertEquals(full.plan().contentHash(), reused.plan().contentHash(),
            "the reused plan folds to the same value as the full rebuild of its input");
        assertEquals(full.plan().graph().nodes(), reused.plan().graph().nodes());
        assertEquals(full.plan().commitOrder(), reused.plan().commitOrder());
        assertEquals(full.plan().domainModes(), reused.plan().domainModes());
    }

    @Test
    void aChangedDeclarationIsAFullFallbackInsideItsBudget() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, chain(), null));
        List<JobDeclaration> changed = new ArrayList<>(chain());
        changed.set(1, declaration("a/1", 5, List.of("a/0")));

        TickPlanPlanner.Result rebuilt = TickPlanPlanner.plan(
            input(3L, changed, first.plan().graph()));
        TickPlanPlanner.Result full = TickPlanPlanner.plan(input(3L, changed, null));

        assertTrue(rebuilt.ok(), () -> "refused: " + rebuilt.code());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, rebuilt.recompute().mode());
        assertTrue(rebuilt.recompute().fullWork() > 0);
        assertTrue(rebuilt.recompute().fullWork() <= rebuilt.recompute().fullBudget());
        assertEquals(full.plan().contentHash(), rebuilt.plan().contentHash());
    }

    @Test
    void aPlanThatDoesNotFitItsWorkBudgetIsRefusedWithoutAPartialPlan() {
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(input(2L, dense(64), null));

        assertFalse(refused.ok());
        assertEquals(RejectCode.SCALE_BUDGET_EXCEEDED, refused.code());
        assertNull(refused.plan());
        assertNotNull(refused.recompute());
        assertFalse(refused.recompute().ok());
        assertNull(refused.recompute().graph());
        assertTrue(refused.recompute().fullWork() <= refused.recompute().fullBudget(),
            "the refusal names a bounded attempt, not an unbounded one");
    }

    @Test
    void aRefusedPlanPublishesNothingAndThePreviousGraphStaysUsable() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, chain(), null));
        assertTrue(first.ok());
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(
            input(3L, dense(64), first.plan().graph()));
        assertFalse(refused.ok());
        assertNull(refused.plan(), "a refused plan publishes no partial graph");

        TickPlanPlanner.Result recovered = TickPlanPlanner.plan(
            input(4L, chain(), first.plan().graph()));
        assertTrue(recovered.ok(), () -> "refused: " + recovered.code());
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, recovered.recompute().mode());
        assertEquals(first.plan().graph().nodes(), recovered.plan().graph().nodes(),
            "the graph used after the refusal is the last accepted one, not the refused one");
    }

    @Test
    void theCoveredPlanningPathPerformsNoUnbudgetedFullRecompute() {
        AtomicLong unbudgetedFullRecomputes = new AtomicLong();
        AtomicLong fullRecomputes = new AtomicLong();
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, chain(), null));
        TickPlanPlanner.Result reused = TickPlanPlanner.plan(
            input(3L, chain(), first.plan().graph()));
        List<JobDeclaration> changed = new ArrayList<>(chain());
        changed.set(2, declaration("a/2", 7, List.of("a/1")));
        TickPlanPlanner.Result fallback = TickPlanPlanner.plan(
            input(4L, changed, reused.plan().graph()));
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(
            input(5L, dense(64), fallback.plan().graph()));

        List<TickPlanPlanner.Result> results = List.of(first, reused, fallback, refused);
        for (TickPlanPlanner.Result result : results) {
            JobGraphBuilder.Recompute recompute = result.recompute();
            if (recompute == null || !recompute.ok()
                || recompute.mode() == JobGraphBuilder.RecomputeMode.REUSED) {
                continue;
            }
            fullRecomputes.incrementAndGet();
            assertTrue(recompute.fullBudget() > 0);
            assertTrue(recompute.fullWork() <= recompute.fullBudget());
            if (!recompute.budgeted()) {
                unbudgetedFullRecomputes.incrementAndGet();
            }
        }

        assertTrue(fullRecomputes.get() >= 2, "a bootstrap and a fallback were covered");
        assertEquals(0L, unbudgetedFullRecomputes.get(),
            "recompute.unbounded=0 (test-only diagnostic; no readout key)");
    }

    @Test
    void anEmptyDeclarationSetPlansAnEmptyGraphAndThenReusesIt() {
        TickPlanPlanner.Result first = TickPlanPlanner.plan(input(2L, List.of(), null));
        TickPlanPlanner.Result reused = TickPlanPlanner.plan(
            input(3L, List.of(), first.plan().graph()));

        assertTrue(first.ok());
        assertTrue(first.plan().graph().empty());
        assertEquals(JobGraphBuilder.RecomputeMode.BOOTSTRAP, first.recompute().mode());
        assertTrue(reused.ok());
        assertTrue(reused.plan().graph().empty());
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, reused.recompute().mode());
        assertEquals(0, reused.plan().commitOrder().size());
    }
}
