/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run-scoped count of full graph builds that started without a work budget: the unbudgeted
 * freeze branch counts even when the build is then refused, budgeted bootstrap/fallback rebuilds do
 * not count, the diagnostic freeze neither counts nor covers, and a reset starts a new generation. */
class RecomputeUnboundedTest {

    private static final String WORLD = "world-a";

    private static JobDeclaration declaration(String key, List<String> predecessors) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, predecessors, 0, key,
            "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
    }

    private static List<JobDeclaration> chain() {
        return List.of(declaration("a/0", List.of()), declaration("a/1", List.of("a/0")),
            declaration("a/2", List.of("a/1")));
    }

    @BeforeEach
    void startANewGeneration() {
        RecomputeUnbounded.reset();
    }

    @Test
    void aFreshGenerationHasNoReadingAndAdvancesTheGeneration() {
        RecomputeUnbounded.reset();
        long generation = RecomputeUnbounded.generation();

        assertFalse(RecomputeUnbounded.reading().isPresent());

        RecomputeUnbounded.reset();
        assertEquals(generation + 1L, RecomputeUnbounded.generation());
        assertFalse(RecomputeUnbounded.reading().isPresent());
    }

    @Test
    void anUnbudgetedBuildStartIsCountedEvenWhenTheBuildIsRefused() {
        List<JobDeclaration> cyclic = List.of(declaration("a/0", List.of("a/1")),
            declaration("a/1", List.of("a/0")));

        JobGraphBuilder.Freeze refused = JobGraphBuilder.freeze(cyclic, 10L, 2L, 1L, 64);

        assertFalse(refused.ok());
        assertEquals(RejectCode.DAG_CYCLE, refused.code());
        assertTrue(RecomputeUnbounded.reading().isPresent(),
            "the unbudgeted branch started before the validation refused the graph");
        assertEquals(1L, RecomputeUnbounded.reading().getAsLong());
    }

    @Test
    void anAcceptedUnbudgetedBuildIsCountedAndCoversTheSource() {
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(chain(), 10L, 2L, 1L, 64);

        assertTrue(freeze.ok());
        assertEquals(1L, RecomputeUnbounded.reading().orElse(-1L));
    }

    @Test
    void twoUnbudgetedBuildsAccumulateAndTheDuplicateReadoutStaysOneNumber() {
        JobGraphBuilder.freeze(chain(), 10L, 2L, 1L, 64);
        JobGraphBuilder.freeze(chain(), 11L, 3L, 1L, 64);

        assertEquals(2L, RecomputeUnbounded.reading().orElse(-1L));
    }

    @Test
    void aBudgetedBootstrapCoversTheSourceWithoutCounting() {
        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, chain(), 10L, 2L, 1L, 64,
                JobGraphBuilder.scanWorkBudget(64), JobGraphBuilder.fullWorkBudget(64)));

        assertTrue(recompute.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.BOOTSTRAP, recompute.mode());
        assertTrue(RecomputeUnbounded.reading().isPresent(),
            "a budgeted full build is a covered source, just not an unbudgeted one");
        assertEquals(0L, RecomputeUnbounded.reading().getAsLong());
    }

    @Test
    void aBudgetedFallbackCoversTheSourceWithoutCounting() {
        JobGraphBuilder.Freeze previous = JobGraphBuilder.freezeDiagnostic(chain(), 9L, 1L, 1L, 64);
        List<JobDeclaration> changed = List.of(declaration("a/0", List.of()),
            declaration("a/1", List.of("a/0")), declaration("a/3", List.of("a/1")));

        JobGraphBuilder.Recompute recompute = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(previous.graph(), changed, 10L, 2L, 1L, 64,
                JobGraphBuilder.scanWorkBudget(64), JobGraphBuilder.fullWorkBudget(64)));

        assertTrue(recompute.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, recompute.mode());
        assertEquals(0L, RecomputeUnbounded.reading().orElse(-1L));
    }

    @Test
    void aRefusalThatNeverStartsABuildNeitherCountsNorCovers() {
        JobGraphBuilder.Recompute refused = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, chain(), 10L, 2L, 1L, 2,
                JobGraphBuilder.scanWorkBudget(2), JobGraphBuilder.fullWorkBudget(2)));

        assertFalse(refused.ok());
        assertFalse(RecomputeUnbounded.reading().isPresent(),
            "no full build started, so the source cannot claim a zero");
    }

    @Test
    void aBudgetedFullBuildThatIsRefusedCoversButDoesNotCount() {
        List<JobDeclaration> dense = new java.util.ArrayList<>();
        for (int index = 0; index < 64; index++) {
            List<String> predecessors = new java.util.ArrayList<>();
            for (int earlier = 0; earlier < index; earlier++) {
                predecessors.add("a/" + earlier);
            }
            dense.add(declaration("a/" + index, predecessors));
        }

        JobGraphBuilder.Recompute refused = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(null, dense, 10L, 2L, 1L, 64,
                JobGraphBuilder.scanWorkBudget(64), JobGraphBuilder.fullWorkBudget(64)));

        assertFalse(refused.ok());
        assertEquals(RejectCode.SCALE_BUDGET_EXCEEDED, refused.code());
        assertEquals(0L, RecomputeUnbounded.reading().orElse(-1L),
            "a budgeted rebuild that ran out of budget is not an unbudgeted rebuild");
    }

    @Test
    void theDiagnosticFreezeNeitherCountsNorCovers() {
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(chain(), 10L, 1L, 1L, 64);

        assertTrue(freeze.ok());
        assertFalse(RecomputeUnbounded.reading().isPresent(),
            "a self-check build is not a planning execution");
    }

    @Test
    void theResetClearsTheCountAndTheCoverageOfTheOldGeneration() {
        JobGraphBuilder.freeze(chain(), 10L, 2L, 1L, 64);
        assertEquals(1L, RecomputeUnbounded.reading().orElse(-1L));
        long generation = RecomputeUnbounded.generation();

        RecomputeUnbounded.reset();

        assertFalse(RecomputeUnbounded.reading().isPresent());
        assertEquals(generation + 1L, RecomputeUnbounded.generation());
    }
}
