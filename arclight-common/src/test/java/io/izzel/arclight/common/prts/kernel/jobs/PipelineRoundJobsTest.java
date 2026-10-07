/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the chunk pipeline's own rounds are declared as: one job per mailbox and world pair that
 * carried a round, nothing for a round whose mailbox no world was read for, and nothing at all for a
 * window in which no round happened. */
class PipelineRoundJobsTest {

    private static final String WORLD = "minecraft:overworld";

    private static JobIntake intake() {
        return new JobIntake(() -> 64);
    }

    @Test
    void theRoundsOfOneMailboxBecomeOneJobOfThatWorld() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerTask(WORLD, "worldgen");

        assertEquals(1, jobs.declareInto(intake));
        List<JobDeclaration> declared = intake.take();
        assertEquals(1, declared.size());
        JobDeclaration declaration = declared.get(0);
        assertEquals("chunk-pipeline/" + WORLD + "/worldgen", declaration.key());
        assertEquals(WORLD, declaration.worldId());
        assertEquals(PipelineRoundJobs.DOMAIN, declaration.domainId());
        assertEquals(3, declaration.batchBound());
        assertEquals(1, declaration.writeSet().size());
        assertEquals(WORLD, declaration.writeSet().get(0).worldId());
        assertEquals(1, declaration.ownerDemands().size());
        assertEquals(PipelineRoundJobs.OWNER_SITE, declaration.ownerDemands().get(0).holderSiteId());
        assertTrue(declaration.predecessors().isEmpty());
        assertEquals(3L, jobs.roundsSeen());
        assertEquals(1L, jobs.declared());
        assertEquals(1L, jobs.lastDeclared());
        assertEquals(3L, jobs.lastRounds());
    }

    @Test
    void aRoundOfAMailboxWithoutAWorldDeclaresNothing() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();
        jobs.ownerRound(PrtsPipelineRows.UNPLACED_WORLD, "worldgen");
        jobs.ownerRound(null, "light");
        jobs.ownerRound(WORLD, "");

        assertEquals(0, jobs.declareInto(intake));
        assertTrue(intake.take().isEmpty(), "an unplaced round must not become an empty node");
        assertEquals(0L, jobs.roundsSeen());
        assertEquals(3L, jobs.roundsUnplaced());
        assertEquals(0L, jobs.declared());
    }

    @Test
    void aWindowWithoutARoundDeclaresNothingTwice() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();

        assertEquals(0, jobs.declareInto(intake));
        assertEquals(0, jobs.declareInto(intake));
        assertTrue(intake.take().isEmpty());
        assertEquals(2L, jobs.windows());
        assertEquals(0L, jobs.declared());
        assertEquals(0L, jobs.lastRounds());
    }

    @Test
    void everyWorldAndMailboxPairOfOneWindowIsDeclaredOnce() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();
        jobs.ownerRound(WORLD, "light");
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound("minecraft:the_nether", "worldgen");

        assertEquals(3, jobs.declareInto(intake));
        List<JobDeclaration> declared = intake.take();
        assertEquals(3, declared.size());
        assertEquals("chunk-pipeline/minecraft:overworld/light", declared.get(0).key());
        assertEquals("chunk-pipeline/minecraft:overworld/worldgen", declared.get(1).key());
        assertEquals("chunk-pipeline/minecraft:the_nether/worldgen", declared.get(2).key());
        assertNull(jobs.traceOf("chunk-pipeline/minecraft:overworld/nowhere"));
        assertNotNull(jobs.traceOf("chunk-pipeline/minecraft:the_nether/worldgen"));
        assertEquals(1L, jobs.traceOf("chunk-pipeline/minecraft:overworld/light").handle());
    }

    @Test
    void theDeclarationsOfOneWindowFreezeIntoNodesThatTraceBackToTheirRounds() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound(WORLD, "light");
        jobs.declareInto(intake);

        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(intake.take(), 7L, 1L, 1L, 64);
        assertTrue(freeze.ok(), () -> "refused: " + freeze.code() + " " + freeze.item());
        assertEquals(2, freeze.graph().nodeCount());
        for (JobGraph.Node node : freeze.graph().nodes()) {
            PipelineRoundJobs.Trace trace = jobs.traceOf(node.key());
            assertNotNull(trace, () -> "node " + node.key() + " has no declaration behind it");
            assertEquals(node.worldId(), trace.worldId());
            assertEquals(node.batchBound(), trace.rounds());
            assertEquals(1, node.ownerDemands().size());
            assertEquals(PipelineRoundJobs.DOMAIN, node.domainId());
        }
        assertEquals(3L, jobs.coveredRounds());
        assertEquals(2L, jobs.lastDeclared());
    }

    @Test
    void aDeclinedDeclarationIsCountedAndCarriesNoRound() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = new JobIntake(() -> 1);
        jobs.ownerRound(WORLD, "worldgen");
        jobs.ownerRound(WORLD, "light");
        jobs.ownerRound(WORLD, "sorter");

        assertEquals(3, jobs.declareInto(intake));
        assertEquals(1L, jobs.declared());
        assertEquals(2L, jobs.refused());
        assertEquals(1L, jobs.lastDeclared());
        assertEquals(1L, jobs.lastRounds());
        assertEquals(1, intake.depth());
    }

    @Test
    void theCountersAreClearedByReset() {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        JobIntake intake = intake();
        jobs.ownerRound(WORLD, "worldgen");
        jobs.declareInto(intake);
        jobs.reset();

        assertEquals(0L, jobs.windows());
        assertEquals(0L, jobs.roundsSeen());
        assertEquals(0L, jobs.declared());
        assertNull(jobs.traceOf("chunk-pipeline/" + WORLD + "/worldgen"), "a reset drains the traces");
        assertEquals(0, jobs.declareInto(intake), "a cleared window declares nothing");
    }
}
