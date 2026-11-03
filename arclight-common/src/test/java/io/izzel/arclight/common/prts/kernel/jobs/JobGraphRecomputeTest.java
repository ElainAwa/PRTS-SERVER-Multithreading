/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded recompute of the job graph: an unchanged declaration set reuses the previous graph
 * without rebuilding it, a changed set is rebuilt only inside its explicit fallback budget, and a
 * request that cannot pay for its work is refused without publishing a graph. The work counters are
 * structural units - declarations, nodes and edges touched - not a clock. */
class JobGraphRecomputeTest {

    private static final String WORLD = "world-a";
    private static final int CAP = 64;

    private static JobDeclaration declaration(String key, int priority, List<String> predecessors) {
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

    private static JobGraphBuilder.RecomputeRequest request(JobGraph previous,
                                                            List<JobDeclaration> declarations,
                                                            long tick, long plan, long generation) {
        return new JobGraphBuilder.RecomputeRequest(previous, declarations, tick, plan, generation,
            CAP, JobGraphBuilder.scanWorkBudget(CAP), JobGraphBuilder.fullWorkBudget(CAP));
    }

    private static void assertSameGraph(JobGraph expected, JobGraph actual) {
        assertEquals(expected.nodes(), actual.nodes());
        assertEquals(expected.order(), actual.order());
        assertEquals(expected.ready(), actual.ready());
        assertEquals(expected.affinity(), actual.affinity());
        assertEquals(expected.edgeCount(), actual.edgeCount());
        assertEquals(expected.splitIntents(), actual.splitIntents());
        assertEquals(expected.queueCap(), actual.queueCap());
    }

    @Test
    void unchangedDeclarationsReuseThePreviousGraph() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, declarations, 10L, 2L, 2L));

        assertTrue(recompute.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, recompute.mode());
        assertEquals(0, recompute.dirtyNodes());
        assertEquals(0, recompute.fullWork());
        assertTrue(recompute.scanWork() > 0, "the scan of the unchanged set is real work");
        assertTrue(recompute.scanWork() <= recompute.scanBudget());
        assertTrue(recompute.budgeted());
        assertEquals(10L, recompute.graph().tickIndex());
        assertEquals(2L, recompute.graph().planSequence());
        assertEquals(2L, recompute.graph().worldSetGeneration());
        assertSameGraph(JobGraphBuilder.freeze(declarations, 10L, 2L, 2L, CAP).graph(),
            recompute.graph());
    }

    @Test
    void aReorderedDeclarationListStillReusesTheGraph() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        List<JobDeclaration> reversed = new ArrayList<>(declarations);
        Collections.reverse(reversed);

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, reversed, 10L, 2L, 1L));

        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, recompute.mode(),
            "the freeze sorts the declarations, so the input order is not a change");
        assertSameGraph(JobGraphBuilder.freeze(reversed, 10L, 2L, 1L, CAP).graph(),
            recompute.graph());
    }

    @Test
    void aChangedDeclarationIsRebuiltInsideTheFallbackBudget() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        List<JobDeclaration> changed = new ArrayList<>(declarations);
        changed.set(1, declaration("a/1", 5, List.of("a/0")));

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, changed, 10L, 2L, 1L));

        assertTrue(recompute.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, recompute.mode());
        assertTrue(recompute.dirtyNodes() > 0);
        assertTrue(recompute.fullWork() > 0);
        assertTrue(recompute.fullWork() <= recompute.fullBudget());
        assertSameGraph(JobGraphBuilder.freeze(changed, 10L, 2L, 1L, CAP).graph(),
            recompute.graph());
    }

    @Test
    void anAddedAndARemovedDeclarationAreRebuilt() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        List<JobDeclaration> added = new ArrayList<>(declarations);
        added.add(declaration("a/3", 3, List.of("a/2")));
        List<JobDeclaration> removed = new ArrayList<>(declarations);
        removed.remove(2);

        JobGraphBuilder.Recompute addedRecompute = JobGraphBuilder.recompute(
            request(previous, added, 10L, 2L, 1L));
        JobGraphBuilder.Recompute removedRecompute = JobGraphBuilder.recompute(
            request(previous, removed, 10L, 2L, 1L));

        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, addedRecompute.mode());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, removedRecompute.mode());
        assertSameGraph(JobGraphBuilder.freeze(added, 10L, 2L, 1L, CAP).graph(),
            addedRecompute.graph());
        assertSameGraph(JobGraphBuilder.freeze(removed, 10L, 2L, 1L, CAP).graph(),
            removedRecompute.graph());
    }

    @Test
    void aStalePreviousGraphIsReusedWithTheNewHeader() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 1L, 7L, 1L, CAP).graph();

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, declarations, 40L, 9L, 3L));

        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, recompute.mode(),
            "the graph content does not depend on the tick or on the plan sequence");
        assertEquals(40L, recompute.graph().tickIndex());
        assertEquals(9L, recompute.graph().planSequence());
        assertEquals(3L, recompute.graph().worldSetGeneration());
        assertSameGraph(JobGraphBuilder.freeze(declarations, 40L, 9L, 3L, CAP).graph(),
            recompute.graph());
    }

    @Test
    void theDirtyScanChargesEveryNodeAndEdgeItTouches() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, declarations, 10L, 2L, 1L));

        assertEquals(11, recompute.scanWork(),
            "three previous nodes indexed, three declarations looked up and compared, two edges");
    }

    @Test
    void theFullRebuildChargesEveryStructuralStepAndStopsAtItsBudget() {
        List<JobDeclaration> declarations = chain();
        JobGraphBuilder.Recompute exact = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, declarations, 10L, 2L, 1L, CAP,
                JobGraphBuilder.scanWorkBudget(CAP), 31));
        JobGraphBuilder.Recompute oneShort = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, declarations, 10L, 2L, 1L, CAP,
                JobGraphBuilder.scanWorkBudget(CAP), 30));

        assertTrue(exact.ok());
        assertEquals(31, exact.fullWork(), "snapshot, validation, numbering, linking and ordering");
        assertFalse(oneShort.ok());
        assertEquals(RejectCode.SCALE_BUDGET_EXCEEDED, oneShort.code());
        assertNull(oneShort.graph());
        assertTrue(oneShort.fullWork() <= 30);
    }

    @Test
    void anOverScanBudgetFallsBackToTheExplicitFullBudget() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        List<JobDeclaration> changed = new ArrayList<>(declarations);
        changed.set(2, declaration("a/2", 9, List.of("a/1")));

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(previous, changed, 10L, 2L, 1L, CAP, 1,
                JobGraphBuilder.fullWorkBudget(CAP)));

        assertTrue(recompute.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, recompute.mode());
        assertTrue(recompute.scanWork() <= 1);
        assertTrue(recompute.fullWork() <= recompute.fullBudget());
        assertSameGraph(JobGraphBuilder.freeze(changed, 10L, 2L, 1L, CAP).graph(),
            recompute.graph());
    }

    @Test
    void aRequestWithoutABudgetIsRefusedInsteadOfPerformed() {
        List<JobDeclaration> declarations = chain();

        JobGraphBuilder.Recompute bootstrap = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, declarations, 10L, 2L, 1L, CAP, 0, 0));
        JobGraphBuilder.Recompute fallback = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(
                JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph(),
                List.of(), 10L, 2L, 1L, CAP, 0, 0));

        assertFalse(bootstrap.ok());
        assertEquals(RejectCode.RECOMPUTE_UNBOUNDED, bootstrap.code());
        assertNull(bootstrap.graph());
        assertFalse(bootstrap.budgeted());
        assertFalse(fallback.ok());
        assertEquals(RejectCode.RECOMPUTE_UNBOUNDED, fallback.code());
        assertEquals(0, fallback.fullWork(), "no full rebuild ran without a budget");
    }

    @Test
    void theEmptyGraphIsBootstrapAndThenReused() {
        JobGraphBuilder.Recompute bootstrap = JobGraphBuilder.recompute(
            request(null, List.of(), 10L, 2L, 1L));
        JobGraphBuilder.Recompute reused = JobGraphBuilder.recompute(
            request(bootstrap.graph(), List.of(), 11L, 3L, 1L));

        assertTrue(bootstrap.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.BOOTSTRAP, bootstrap.mode());
        assertTrue(bootstrap.graph().empty());
        assertTrue(reused.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, reused.mode());
        assertTrue(reused.graph().empty());
        assertEquals(0, reused.scanWork());
    }

    @Test
    void aRefusedGraphIsRefusedByTheBoundedRecomputeToo() {
        List<JobDeclaration> declarations = chain();
        JobGraph previous = JobGraphBuilder.freeze(declarations, 9L, 1L, 1L, CAP).graph();
        List<JobDeclaration> duplicate = new ArrayList<>(declarations);
        duplicate.add(declaration("a/0", 0, List.of()));

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            request(previous, duplicate, 10L, 2L, 1L));

        assertFalse(recompute.ok());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, recompute.code());
        assertNull(recompute.graph());
    }

    @Test
    void aDeclarationSetOverTheQueueCapIsRefusedBeforeAnyWork() {
        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, chain(), 10L, 2L, 1L, 2,
                JobGraphBuilder.scanWorkBudget(2), JobGraphBuilder.fullWorkBudget(2)));

        assertFalse(recompute.ok());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, recompute.code());
        assertEquals(0, recompute.scanWork());
        assertEquals(0, recompute.fullWork());
    }

    @Test
    void theBudgetsAreDeclaredFromTheQueueBoundAndNeverZero() {
        for (int cap = 1; cap <= 256; cap *= 2) {
            assertTrue(JobGraphBuilder.scanWorkBudget(cap) > 0);
            assertTrue(JobGraphBuilder.fullWorkBudget(cap) > 0);
            assertTrue(JobGraphBuilder.scanWorkBudget(cap)
                < JobGraphBuilder.fullWorkBudget(cap),
                "the fallback is allowed a wider bound than the scan");
        }
        assertNotNull(JobGraphBuilder.RecomputeMode.valueOf("REUSED"));
    }
}
