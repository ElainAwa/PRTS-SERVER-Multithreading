/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the chunk pipeline's own submits are declared as: one job per batch the ledger cut out of
 * the tasks submitted before the freeze, nothing for a mailbox no task was submitted on, and nothing
 * at all for a window in which the pipeline submitted nothing. */
class PipelineRoundJobsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER = "minecraft:the_nether";
    private static final String MAILBOX = "worldgen";
    private static final Object PROCESSOR = new Object();
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final long TICK = 5L;
    private static final long PLAN = 7L;

    private static JobIntake intake() {
        return new JobIntake(() -> 64);
    }

    /** Binds a producer to a ledger and mints one task per cell; the sorter is not involved, the
     * ledger is the source of the cut. */
    private static PipelineRoundJobs producer(PipelineBatchLedger ledger) {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        jobs.bind(ledger);
        return jobs;
    }

    private static void mint(PipelineBatchLedger ledger, String world, String mailbox, long cell) {
        ledger.mint(PROCESSOR, world, mailbox, cell, TICK);
    }

    @Test
    void theTasksOfOneMailboxBecomeOneJobOfThatWorld() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        mint(ledger, WORLD, MAILBOX, CELL);
        mint(ledger, WORLD, MAILBOX, CELL);
        mint(ledger, WORLD, MAILBOX, CELL);

        assertEquals(1, jobs.declareInto(intake, PLAN, TICK));
        List<JobDeclaration> declared = intake.take();
        assertEquals(1, declared.size());
        JobDeclaration declaration = declared.get(0);
        assertTrue(declaration.key().startsWith(PipelineRoundJobs.keyOf(WORLD, MAILBOX) + "#"),
            declaration.key());
        assertEquals(WORLD, declaration.worldId());
        assertEquals(PipelineRoundJobs.DOMAIN, declaration.domainId());
        assertEquals(MAILBOX, declaration.affinityKey());
        assertEquals(3, declaration.batchBound());
        assertEquals(1, declaration.writeSet().size());
        assertEquals(WORLD, declaration.writeSet().get(0).worldId());
        assertEquals(1, declaration.ownerDemands().size());
        assertEquals(PipelineRoundJobs.OWNER_SITE, declaration.ownerDemands().get(0).holderSiteId());
        assertTrue(declaration.predecessors().isEmpty());
        assertNotNull(declaration.range());
        assertEquals(JobDeclaration.RangeGranularity.CHUNK, declaration.range().granularity());
        assertEquals(1, declaration.range().cells().length);
        assertEquals(CELL, declaration.range().cells()[0]);
        assertEquals(1L, jobs.declared());
        assertEquals(1L, jobs.lastDeclared());
        assertEquals(3L, jobs.lastRounds());
        assertEquals(3L, jobs.coveredRounds());
    }

    @Test
    void aMailboxNoTaskWasSubmittedOnDeclaresNothing() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        jobs.ownerRound(PrtsPipelineRows.UNPLACED_WORLD, MAILBOX);
        jobs.ownerRound(null, "light");
        jobs.ownerRound(WORLD, "");

        assertEquals(0, jobs.declareInto(intake, PLAN, TICK));
        assertTrue(intake.take().isEmpty(), "an unplaced round must not become an empty node");
        assertEquals(0L, jobs.roundsSeen());
        assertEquals(3L, jobs.roundsUnplaced());
        assertEquals(0L, jobs.declared());
    }

    @Test
    void aWindowWithoutATaskDeclaresNothingTwice() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();

        assertEquals(0, jobs.declareInto(intake, PLAN, TICK));
        assertEquals(0, jobs.declareInto(intake, PLAN + 1L, TICK + 1L));
        assertTrue(intake.take().isEmpty());
        assertTrue(jobs.candidateDeclarations().isEmpty(), "no batch means no artefact to carry");
        assertEquals(2L, jobs.windows());
        assertEquals(0L, jobs.declared());
        assertEquals(0L, jobs.lastRounds());
    }

    @Test
    void everyWorldAndMailboxPairOfOneWindowIsDeclaredOnce() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        mint(ledger, WORLD, "light", CELL);
        mint(ledger, WORLD, MAILBOX, CELL);
        mint(ledger, OTHER, MAILBOX, CELL);

        assertEquals(3, jobs.declareInto(intake, PLAN, TICK));
        List<JobDeclaration> declared = intake.take();
        assertEquals(3, declared.size());
        assertEquals(PipelineRoundJobs.keyOf(WORLD, "light"), prefix(declared.get(0).key()));
        assertEquals(PipelineRoundJobs.keyOf(WORLD, MAILBOX), prefix(declared.get(1).key()));
        assertEquals(PipelineRoundJobs.keyOf(OTHER, MAILBOX), prefix(declared.get(2).key()));
        assertNull(jobs.traceOf(PipelineRoundJobs.keyOf(WORLD, "nowhere")));
        assertNotNull(jobs.traceOf(declared.get(2).key()));
        assertEquals(1L, jobs.traceOf(declared.get(0).key()).handle());
    }

    @Test
    void theDeclarationsOfOneWindowFreezeIntoNodesThatTraceBackToTheirTasks() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        mint(ledger, WORLD, MAILBOX, CELL);
        mint(ledger, WORLD, MAILBOX, CELL);
        mint(ledger, WORLD, "light", ChunkPos.asLong(1, 0));
        jobs.declareInto(intake, PLAN, TICK);

        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(intake.take(), TICK, PLAN, 1L, 64);
        assertTrue(freeze.ok(), () -> "refused: " + freeze.code() + " " + freeze.item());
        assertEquals(2, freeze.graph().nodeCount());
        for (JobGraph.Node node : freeze.graph().nodes()) {
            PipelineRoundJobs.Trace trace = jobs.traceOf(node.key());
            assertNotNull(trace, () -> "node " + node.key() + " has no declaration behind it");
            assertEquals(node.worldId(), trace.worldId());
            assertEquals(node.batchBound(), trace.rounds());
            assertTrue(node.affinity().endsWith("/" + trace.mailbox()), node.affinity());
            assertEquals(1, node.ownerDemands().size());
            assertEquals(PipelineRoundJobs.DOMAIN, node.domainId());
            assertNotNull(node.range());
        }
        assertEquals(3L, jobs.coveredRounds());
        assertEquals(2L, jobs.lastDeclared());
    }

    @Test
    void aDeclinedDeclarationIsCountedAndCarriesNoTask() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = new JobIntake(() -> 1);
        mint(ledger, WORLD, "worldgen", CELL);
        mint(ledger, WORLD, "light", CELL);
        mint(ledger, WORLD, "sorter", CELL);

        assertEquals(3, jobs.declareInto(intake, PLAN, TICK));
        assertEquals(1L, jobs.declared());
        assertEquals(2L, jobs.refused());
        assertEquals(1L, jobs.lastDeclared());
        assertEquals(1L, jobs.lastRounds());
        assertEquals(1, intake.depth());
    }

    @Test
    void aBatchCandidateCarriesTheCellsItsPlanningRangeWasBuiltFrom() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        long first = ChunkPos.asLong(0, 0);
        long second = ChunkPos.asLong(1, 0);
        mint(ledger, WORLD, MAILBOX, second);
        mint(ledger, WORLD, MAILBOX, first);
        jobs.declareInto(intake, PLAN, TICK);

        List<FrozenDeclarations.Candidate> candidates = jobs.candidateDeclarations();
        assertEquals(1, candidates.size());
        FrozenDeclarations.Candidate candidate = candidates.get(0);
        assertTrue(candidate.artefactDeclared(),
            "the cells a batch covers are the artefact the candidate carries");
        assertTrue(candidate.rangeDeclared());
        assertEquals("", candidate.targetStatus());
        assertNull(candidate.chunk());

        PipelineBatchLedger.BatchId batch = new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 1L,
            PLAN);
        long[] cells = ledger.cellsOf(batch);
        assertArrayEquals(new long[] {first, second}, cells);
        assertArrayEquals(cells, candidate.range().cells(),
            "the candidate reads the binding the planning range is built from");
        assertArrayEquals(cells, intake.take().get(0).range().cells(),
            "the planning range and the artefact range are one cut");

        List<FrozenDeclarations.Assignment> places = List.of(new FrozenDeclarations.Assignment(
            WORLD, PipelineRoundJobs.DOMAIN, candidate.nodeKey(), 0));
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(
            new FrozenDeclarations.FreezeKey("", 1L), PLAN, candidates, places);
        assertNotNull(sealed.declarations());
        assertEquals(FrozenDeclarations.NOT_WIRED, sealed.declarations().state(),
            "an entry carries one chunk and one status, so the batch range writes no entry");
        assertEquals(0, sealed.declarations().size());
    }

    @Test
    void anEmptyBatchRangeDeclaresNoArtefactAndSealsNotWired() {
        FrozenDeclarations.Candidate empty = new FrozenDeclarations.Candidate(WORLD,
            PipelineRoundJobs.DOMAIN, PipelineRoundJobs.keyOf(WORLD, MAILBOX) + "#1", null, "",
            new FrozenDeclarations.DeclaredRange(new long[0]), 1L, 7L);
        assertFalse(empty.artefactDeclared(), "an empty range declares no artefact");
        assertFalse(empty.rangeDeclared());
        assertFalse(empty.entryDeclared());
        List<FrozenDeclarations.Assignment> places = List.of(new FrozenDeclarations.Assignment(
            WORLD, PipelineRoundJobs.DOMAIN, empty.nodeKey(), 0));
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(
            new FrozenDeclarations.FreezeKey("", 1L), PLAN, List.of(empty), places);
        assertNotNull(sealed.declarations());
        assertEquals(FrozenDeclarations.NOT_WIRED, sealed.declarations().state());
        assertEquals(0, sealed.declarations().size());
    }

    @Test
    void aBatchAlreadyCutIsNotCutAgain() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        mint(ledger, WORLD, MAILBOX, CELL);

        assertEquals(1, jobs.declareInto(intake, PLAN, TICK));
        intake.take();
        assertEquals(0, jobs.declareInto(intake, PLAN + 1L, TICK + 1L),
            "the tasks of an already declared batch are not cut a second time");
        assertEquals(0L, ledger.deferredCount());
    }

    @Test
    void theCountersAreClearedByReset() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = intake();
        mint(ledger, WORLD, MAILBOX, CELL);
        jobs.declareInto(intake, PLAN, TICK);
        jobs.reset();

        assertEquals(0L, jobs.windows());
        assertEquals(0L, jobs.declared());
        assertNull(jobs.traceOf(PipelineRoundJobs.keyOf(WORLD, MAILBOX) + "#1"),
            "a reset drains the traces");
        assertEquals(0, jobs.declareInto(intake, PLAN + 1L, TICK + 1L),
            "a cleared window declares nothing");
    }

    private static String prefix(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }
}
