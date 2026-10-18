/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WriteClaimTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-cell form of a landed batch: the real claim point, the real write path and the real
 * ledger hand one settlement period its payload. The only thing a case installs is the sink; the
 * production constructor is the one without it, and a case drives both to show the plain reports are
 * the same either way. */
class TypedConsumerSettlementTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String MAILBOX = "sorter";
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + MAILBOX;
    private static final String HOLDER = "domain:" + DOMAIN;
    private static final String SITE = "host:server-thread";
    private static final long TICK = 100L;
    private static final long PLAN_SEQUENCE = 7L;
    private static final long ROUNDS = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final long CELL2 = ChunkPos.asLong(1, 0);
    private static final Object LEVEL = new Object();
    private static final WriteSpace SPACE =
        WriteSpace.of(CellKind.SECTION, SectionPos.asLong(0, 4, 0));
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);

    @Test
    void anArmedSettlementAttributesTheWritesOfEachWindowToItsCell() {
        Scratch scratch = new Scratch(true);
        scratch.declare(ROUNDS);

        PipelineBatchLedger.TrackedTask token = scratch.token(CELL);
        scratch.open(token);
        scratch.capture();
        scratch.capture();
        scratch.land();
        scratch.land();
        scratch.close(token);

        List<PipelineWriteClaims.Report> reports = scratch.report();
        assertEquals(1, reports.size());
        assertEquals(2, reports.get(0).rows());
        assertEquals(1, scratch.attributed.size(), "one settlement period receives one payload");
        PipelineWriteClaims.Attributed payload = scratch.attributed.get(0);
        assertTrue(payload.attributed());
        assertEquals(reports.get(0), payload.report(), "the plain report is carried unchanged");
        assertEquals(1, payload.cells().size(), () -> "cells=" + payload.cells());
        assertEquals(CELL, payload.cells().get(0).cell());
        assertEquals(token.id().ordinal(), payload.cells().get(0).taskOrdinal());
        assertEquals(2, payload.cells().get(0).rows(),
            "the rows of the window are counted for its cell");
    }

    @Test
    void aBatchOfTwoCellsIsAttributedPerCellUnderOneReport() {
        Scratch scratch = new Scratch(true);
        scratch.declare(ROUNDS);

        PipelineBatchLedger.TrackedTask first = scratch.token(CELL);
        scratch.open(first);
        scratch.capture();
        scratch.land();
        scratch.close(first);
        PipelineBatchLedger.TrackedTask second = scratch.token(CELL2);
        scratch.open(second);
        scratch.capture();
        scratch.land();
        scratch.land();
        scratch.close(second);

        List<PipelineWriteClaims.Report> reports = scratch.report();
        assertEquals(1, reports.size(), "one seat, one report, whatever the cell count");
        assertEquals(3, reports.get(0).rows());
        PipelineWriteClaims.Attributed payload = scratch.attributed.get(0);
        assertTrue(payload.attributed());
        assertEquals(2, payload.cells().size(), () -> "cells=" + payload.cells());
        assertEquals(CELL, payload.cells().get(0).cell());
        assertEquals(first.id().ordinal(), payload.cells().get(0).taskOrdinal());
        assertEquals(1, payload.cells().get(0).rows());
        assertEquals(CELL2, payload.cells().get(1).cell());
        assertEquals(second.id().ordinal(), payload.cells().get(1).taskOrdinal());
        assertEquals(2, payload.cells().get(1).rows());
    }

    @Test
    void anUnattributedLandingFallsBackToThePlainPathWhole() {
        Scratch scratch = new Scratch(true);
        scratch.declare(ROUNDS);

        PipelineBatchLedger.TrackedTask token = scratch.token(CELL);
        scratch.open(token);
        scratch.capture();
        scratch.land();
        scratch.close(token);
        scratch.open(null);
        scratch.capture();
        scratch.land();
        scratch.close(null);

        List<PipelineWriteClaims.Report> reports = scratch.report();
        assertEquals(1, reports.size());
        assertEquals(2, reports.get(0).rows(), "the plain report counts every landing");
        PipelineWriteClaims.Attributed payload = scratch.attributed.get(0);
        assertFalse(payload.attributed(), "one landing without a task token refuses the batch");
        assertTrue(payload.cells().isEmpty(), "no cell is guessed for the batch");
    }

    @Test
    void theUnarmedPointReportsExactlyWhatTheArmedPointReports() {
        Scratch unarmed = new Scratch(false);
        Scratch armed = new Scratch(true);
        for (Scratch scratch : List.of(unarmed, armed)) {
            scratch.declare(ROUNDS);
            PipelineBatchLedger.TrackedTask token = scratch.token(CELL);
            scratch.open(token);
            scratch.capture();
            scratch.land();
            scratch.land();
            scratch.close(token);
        }

        List<PipelineWriteClaims.Report> first = unarmed.report();
        List<PipelineWriteClaims.Report> second = armed.report();
        assertEquals(first, second, "the armed point builds the same plain reports");
        assertEquals(1, armed.attributed.size());
        assertTrue(armed.attributed.get(0).attributed());
        assertTrue(unarmed.attributed.isEmpty());
        assertFalse(unarmed.claims.settlementArmed());
        assertTrue(armed.claims.settlementArmed());
        assertEquals(unarmed.claims.landedWriteCount(), armed.claims.landedWriteCount());
        assertEquals(unarmed.claims.reportedBatchCount(), armed.claims.reportedBatchCount());
        assertEquals(unarmed.claims.seatedCount(), armed.claims.seatedCount());
        assertEquals(unarmed.claims.disarmedCount(), armed.claims.disarmedCount());
        assertEquals(unarmed.claims.declaredCount(), armed.claims.declaredCount());
        assertEquals(unarmed.claims.landedWithoutNodeCount(), armed.claims.landedWithoutNodeCount());
        assertEquals(0L, unarmed.claims.openWindowFrames());
        assertEquals(0L, armed.claims.openWindowFrames());
    }

    @Test
    void aResetDropsTheAttributionWithTheLanding() {
        Scratch scratch = new Scratch(true);
        scratch.declare(ROUNDS);
        PipelineBatchLedger.TrackedTask token = scratch.token(CELL);
        scratch.open(token);
        scratch.capture();
        scratch.land();
        scratch.close(token);

        scratch.claims.reset();
        assertTrue(scratch.report().isEmpty());
        assertTrue(scratch.attributed.isEmpty());
        assertEquals(0L, scratch.claims.landedWriteCount());
    }

    @Test
    void theReadinessAccessorReadsTheKernelAndProducesNothing() {
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.resetReadings();
        module.markSafePoint();
        try {
            long rangeGeneration = module.rangeSeals().generation();
            long seated = module.claims().seatedCount();
            long declared = module.claims().declaredCount();
            KernelModule.TypedConsumerReadiness readiness = module.typedConsumerReadiness();

            assertFalse(KernelModule.TypedConsumerReadiness.SETTLEMENT_WIRED);
            assertFalse(readiness.effectsBound(), "the module's own log carries no effect store");
            assertFalse(readiness.controllerBound(), "no chain is adopted");
            assertFalse(readiness.obligationsRegistered(), "the factory is handed no registry");
            assertFalse(readiness.attributionArmed(), "the claim point has no settlement sink");
            assertFalse(readiness.inputsBound());
            assertFalse(readiness.ready());
            assertEquals(readiness, module.typedConsumerReadiness(),
                "the readiness is a value of the state it read");
            assertEquals(rangeGeneration, module.rangeSeals().generation(),
                "the accessor starts no lifecycle generation");
            assertEquals(seated, module.claims().seatedCount());
            assertEquals(declared, module.claims().declaredCount());
            assertEquals(0L, module.claims().landedWriteCount());
            assertEquals(0, module.rangeSeals().size());
            assertEquals(0, module.rangeSeals().pinnedCount());
            assertFalse(module.claims().settlementArmed());
            assertNotEquals(0, module.rangeSeals().capacity(),
                "the table the readiness reads is the installed one");
        } finally {
            module.resetReadings();
            module.markSafePoint();
        }
    }

    /** The claim point and the write path of one process, wired the way the kernel wires them; only
     * the level a world is read as and the step the plan holds are fixtures. A scratch is either
     * armed with the settlement sink of the case or left on the production constructor. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);
        private final List<PipelineWriteClaims.Attributed> attributed = new ArrayList<>();
        private final PipelineWriteClaims claims;
        private final JobIntake intake = new JobIntake(() -> 16);
        private final PipelineBatchLedger batches = new PipelineBatchLedger();
        private final PipelineRoundJobs jobs = new PipelineRoundJobs();

        private Scratch(boolean armed) {
            this.claims = armed
                ? new PipelineWriteClaims(versions, guard.worldEpochs(), () -> true,
                    world -> LEVEL, attributed::add)
                : new PipelineWriteClaims(versions, guard.worldEpochs(), () -> true,
                    world -> LEVEL);
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.claimLandedTap((slot, world, tick) -> claims.noteLanded(slot, world));
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD));
            versions.refresh(true);
            slots.refresh(true);
            grants.refresh(true);
            claims.table(slots);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
        }

        private PipelineBatchLedger.TrackedTask token(long cell) {
            return batches.mint(new Object(), WORLD, MAILBOX, cell, TICK);
        }

        private void open(PipelineBatchLedger.TrackedTask token) {
            claims.ownerTaskBegin(WORLD, MAILBOX, token);
        }

        private void close(PipelineBatchLedger.TrackedTask token) {
            claims.ownerTaskEnd(WORLD, MAILBOX, token);
        }

        private WriteControlledSlots.Slot capture() {
            assertEquals(Verdict.CAPTURED, judge(WritePath.BLOCK_WRITE.key()),
                () -> "refusal=" + slots.lastRefusal());
            return slots.lastCoveredSlot();
        }

        private void land() {
            claims.noteLanded(capture(), WORLD);
        }

        private List<PipelineWriteClaims.Report> report() {
            List<PipelineWriteClaims.Report> reports = new ArrayList<>();
            claims.reportLanded(reports::add);
            return reports;
        }

        private Verdict judge(String domainKey) {
            return slots.judge(LEVEL, domainKey, WITNESS, TICK).verdict();
        }

        private JobGraph declare(long tasks) {
            jobs.bind(batches);
            for (long task = 0; task < tasks; task++) {
                batches.mint(MAILBOX, WORLD, MAILBOX, CELL, TICK);
            }
            jobs.declareInto(intake, PLAN_SEQUENCE, TICK);
            JobGraphBuilder.Freeze frozen =
                JobGraphBuilder.freeze(intake.take(), TICK, PLAN_SEQUENCE, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code() + " "
                + frozen.item());
            return publish(frozen.graph());
        }

        private JobGraph publish(JobGraph frozen) {
            Map<OwnershipDomain, Long> granted = new HashMap<>();
            for (JobGraph.Node node : frozen.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    OwnershipDomain domain =
                        new OwnershipDomain(ref.worldId(), WriteLevel.REGION, ref.domainId());
                    granted.put(domain,
                        versions.grant(ref.worldId(), WriteLevel.REGION, ref.domainId()));
                }
            }
            versions.noteGranted(PLAN_SEQUENCE, granted);
            grants.apply(frozen, TICK);
            claims.declare(frozen, PLAN_SEQUENCE, TICK);
            if (!frozen.nodes().isEmpty()) {
                source.step = new WriteControlledSlots.Source.Step(PLAN_SEQUENCE,
                    frozen.nodes().get(0).position());
            }
            return frozen;
        }

        private static final class FixtureSource implements WriteControlledSlots.Source {

            private WriteControlledSlots.Source.Step step = null;

            @Override
            public String worldOf(Object levelRef) {
                return levelRef == LEVEL ? WORLD : "";
            }

            @Override
            public WriteControlledSlots.Source.Step stepOf(String worldId, String domainId,
                                                           String nodeKey) {
                return nodeKey.startsWith(NODE_KEY) ? step : null;
            }
        }
    }
}
