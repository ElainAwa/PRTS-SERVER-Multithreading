/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
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
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The effect row contract of the typed consumer: the identities the real write path names for a
 * claimed write, the record built from them and the refusals of everything that only looks like
 * one. Every case drives the real claim point, the real write guard, the real range table and a
 * real commit log with a real effect store. */
class EffectRowContractTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String MAILBOX = "sorter";
    private static final String HOLDER = "domain:" + DOMAIN;
    private static final String SITE = "host:server-thread";
    private static final long TICK = 100L;
    private static final long PLAN_SEQUENCE = 7L;
    private static final long ROUNDS = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final long CELL2 = ChunkPos.asLong(1, 0);
    private static final Object LEVEL = new Object();
    private static final WriteSpace SPACE = WriteSpace.of(CellKind.SECTION,
        SectionPos.asLong(0, 4, 0), SectionPos.asLong(1, 4, 0));
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);

    @Test
    void aClaimedBatchBecomesOneTypedLineWhoseRecordNamesEveryRow() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.write(8, 64, 9);
        stage.close(token);

        stage.report();

        assertEquals(1, stage.payloads.size(), "one settlement period receives one payload");
        PipelineWriteClaims.Attributed payload = stage.payloads.get(0);
        assertTrue(payload.attributed());
        assertTrue(payload.rowsNamed(), "both writes named their identity");
        assertEquals(1, payload.cells().size());
        assertEquals(2, payload.cells().get(0).rows());
        assertEquals(2, payload.cells().get(0).rowContent().size());
        assertNotEquals(payload.cells().get(0).rowContent().get(0),
            payload.cells().get(0).rowContent().get(1), "two writes at two places are two rows");

        assertEquals(1L, stage.log.typedStored(), "the batch becomes one typed line");
        assertEquals(0L, stage.log.typedRefused());
        CommitLog.Replay replay = stage.log.closeTick();
        CommitLog.SchemaReplay schema = stage.log.lastTyped();
        assertEquals(CommitLog.SCHEMA_TYPED, schema.schemaVersion());
        assertEquals(1, schema.typedSteps().size());
        assertEquals(1, replay.steps().size(), "the typed line is the plain step as well");
        CommitLog.TypedStep step = schema.typedSteps().get(0);
        assertNotNull(step.effect());
        assertEquals(2, step.rows());
        assertEquals(2, step.effect().content().size(), "one effect row per counted row");
        assertTrue(step.effect().effectRef() > 0L);
        assertEquals(step.effect().effectRef(), step.identity().effectRef());
        assertEquals(stage.lastInstance, step.identity().instanceId());
        assertEquals(stage.epoch, step.identity().worldEpoch());
        assertEquals(CELL, step.identity().chunkPos());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, step.disposition());
        assertEquals(0, schema.effectMissing());
        assertEquals(0, stage.log.unreadObligations(), "the record is copied and released");
        for (String row : step.effect().content()) {
            assertTrue(WorldWriteGuard.LandedRow.rowIdentity(row), () -> "row=" + row);
        }
    }

    @Test
    void twoCellsOfOneSeatBecomeTwoTypedLinesWithDistinctReferences() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        stage.publishSeal(CELL2);
        PipelineBatchLedger.TrackedTask first = stage.token(CELL);
        stage.open(first);
        stage.write(8, 64, 8);
        stage.close(first);
        PipelineBatchLedger.TrackedTask second = stage.token(CELL2);
        stage.open(second);
        stage.write(24, 64, 8);
        stage.close(second);

        stage.report();

        PipelineWriteClaims.Attributed payload = stage.payloads.get(0);
        assertTrue(payload.attributed());
        assertEquals(2, payload.cells().size());
        assertEquals(2L, stage.log.typedStored(), "one line per cell of the seat");
        stage.log.closeTick();
        List<CommitLog.TypedStep> steps = stage.log.lastTyped().typedSteps();
        assertEquals(2, steps.size());
        assertNotEquals(steps.get(0).effect().effectRef(), steps.get(1).effect().effectRef(),
            "one reference names one record");
        assertTrue(steps.get(0).effect().effectRef() > 0L);
        assertTrue(steps.get(1).effect().effectRef() > 0L);
        for (CommitLog.TypedStep step : steps) {
            assertEquals(1, step.rows());
            assertEquals(1, step.effect().content().size());
        }
    }

    @Test
    void aBatchWhoseSealIsGoneWritesNoTypedLine() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        stage.table.reset();

        stage.report();

        assertTrue(stage.payloads.get(0).attributed());
        assertEquals(0L, stage.log.typedStored(), "a missing seal leaves the plain path alone");
        assertEquals(1L, stage.settlement.stats().refused());
        assertEquals(0L, stage.settlement.stats().written());
    }

    @Test
    void aBatchWithoutAnAdoptedChainWritesNoTypedLine() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();
        CommitLog own = new CommitLog(() -> 8, stage.orderSource());

        TypedConsumerSettlement unbound = new TypedConsumerSettlement(() -> own, stage.table,
            () -> stage.controller);
        unbound.settle(payload);

        assertEquals(1L, unbound.stats().refused());
        assertEquals(0L, unbound.stats().written(), "no effect store means no typed line");
    }

    @Test
    void aBatchWithoutAControllerNamespaceWritesNoTypedLine() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();

        TypedConsumerSettlement unbound = new TypedConsumerSettlement(() -> stage.log, stage.table,
            () -> null);
        unbound.settle(payload);

        assertEquals(1L, unbound.stats().refused());
        assertEquals(0L, unbound.stats().written(), "no producer namespace means no typed line");
    }

    @Test
    void aBatchWithoutAWriteSetVersionWritesNoTypedLine() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();
        PipelineWriteClaims.Report report = payload.report();
        PipelineWriteClaims.Report unsigned = new PipelineWriteClaims.Report(report.worldId(),
            report.domainId(), report.nodeKey(), report.planSequence(), report.position(),
            report.lifecycle(), 0L, report.writeSetDigest(), report.rows());
        PipelineWriteClaims.Attributed crafted = new PipelineWriteClaims.Attributed(unsigned,
            payload.cells(), true, true);

        stage.settlement.settle(crafted);

        assertEquals(1L, stage.settlement.stats().refused());
        assertEquals(0L, stage.settlement.stats().written());
        assertEquals(0L, stage.log.typedStored());
    }

    @Test
    void rowsThatAreEmptySummariesOrRepeatsAreRefused() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();
        PipelineWriteClaims.Report report = payload.report();
        PipelineWriteClaims.CellRows real = payload.cells().get(0);
        long before = stage.settlement.stats().written();

        List<PipelineWriteClaims.Attributed> forged = new ArrayList<>();
        forged.add(new PipelineWriteClaims.Attributed(report,
            List.of(new PipelineWriteClaims.CellRows(real.cell(), real.taskOrdinal(), 1,
                List.of())), true, true));
        forged.add(new PipelineWriteClaims.Attributed(report,
            List.of(new PipelineWriteClaims.CellRows(real.cell(), real.taskOrdinal(), 1,
                List.of("digest=4f9c2a"))), true, true));
        forged.add(new PipelineWriteClaims.Attributed(report,
            List.of(new PipelineWriteClaims.CellRows(real.cell(), real.taskOrdinal(), 1,
                List.of("state=full"))), true, true));
        forged.add(new PipelineWriteClaims.Attributed(report,
            List.of(new PipelineWriteClaims.CellRows(real.cell(), real.taskOrdinal(), 1,
                List.of("   "))), true, true));
        PipelineWriteClaims.Report wider = new PipelineWriteClaims.Report(report.worldId(),
            report.domainId(), report.nodeKey(), report.planSequence(), report.position(),
            report.lifecycle(), report.expectedVersion(), report.writeSetDigest(),
            report.rows() + 1);
        forged.add(new PipelineWriteClaims.Attributed(wider,
            List.of(new PipelineWriteClaims.CellRows(real.cell(), real.taskOrdinal(), 1,
                real.rowContent())), true, true));
        for (PipelineWriteClaims.Attributed crafted : forged) {
            stage.settlement.settle(crafted);
        }

        assertEquals(0L, stage.settlement.stats().written() - before,
            "no approximation of a row is written as an effect");
        assertEquals(5L, stage.settlement.stats().refused());
        assertEquals(0L, stage.log.typedStored());
        assertEquals(0L, stage.log.typedRefused(), "a refused input never reaches the log");
    }

    @Test
    void aBatchThatCouldNotBeAttributedWritesNothing() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();
        PipelineWriteClaims.Attributed unattributed = new PipelineWriteClaims.Attributed(
            payload.report(), List.of(), false, false);

        stage.settlement.settle(unattributed);

        assertEquals(1L, stage.settlement.stats().refused());
        assertEquals(0L, stage.settlement.stats().written());
        assertEquals(0L, stage.log.typedStored());
    }

    @Test
    void aBatchWhoseWritesNamedNoRowWritesNothing() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();
        PipelineWriteClaims.Attributed unnamed = new PipelineWriteClaims.Attributed(
            payload.report(), payload.cells(), true, false);

        stage.settlement.settle(unnamed);

        assertEquals(1L, stage.settlement.stats().refused());
        assertEquals(0L, stage.settlement.stats().written());
        assertEquals(0L, stage.log.typedStored());
    }

    /** The claim point, the write guard, the range table and a commit log with an effect store wired
     * the way the kernel wires them; only the level a world is read as and the step of the plan are
     * fixtures. A stage either writes its lines through the claim point's own settlement sink or
     * keeps the payload for a case that drives the settlement itself. */
    @Test
    void anAbsentDeclaredSealWritesNoTypedLineAndKeepsThePlainReport() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);

        stage.report();

        assertFalse(stage.payloads.isEmpty());
        PipelineWriteClaims.Attributed payload = stage.payloads.get(0);
        assertTrue(payload.attributed());
        assertTrue(payload.report().rows() > 0, "the plain report carries its rows");
        assertEquals(0L, stage.log.typedStored(),
            "an absent declared seal leaves the typed path at zero");
        assertEquals(0L, stage.settlement.stats().written());
        assertTrue(stage.settlement.stats().refused() >= 1L, "the settlement refuses the batch");
    }

    @Test
    void aDeclaredSealWithoutThePayloadCellWritesNoTypedLine() {
        Stage stage = new Stage();
        stage.declare(ROUNDS);
        stage.publishSeal(CELL);
        PipelineBatchLedger.TrackedTask token = stage.token(CELL);
        stage.open(token);
        stage.write(8, 64, 8);
        stage.close(token);
        PipelineWriteClaims.Attributed payload = stage.drain();

        PipelineWriteClaims.CellRows only = payload.cells().get(0);
        PipelineWriteClaims.CellRows elsewhere = new PipelineWriteClaims.CellRows(CELL2,
            only.taskOrdinal(), only.rows(), only.rowContent());
        PipelineWriteClaims.Attributed crafted = new PipelineWriteClaims.Attributed(
            payload.report(), List.of(elsewhere), true, true);
        stage.settlement.settle(crafted);

        assertEquals(0L, stage.log.typedStored(),
            "a seal without the cell of the payload writes no typed line");
        assertEquals(0L, stage.settlement.stats().written());
        assertEquals(payload.report().rows(), crafted.report().rows(),
            "the plain report is carried unchanged");
    }

    private static final class Stage {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger writeLedger = new WriteLedger();
        private final IntentPayloadDirectory directory = new IntentPayloadDirectory();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, writeLedger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, directory, writeLedger);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);
        private final List<PipelineWriteClaims.Attributed> payloads = new ArrayList<>();
        private boolean settleOnReport = true;
        private final List<PipelineWriteClaims.Report> reports = new ArrayList<>();
        private final CommitNamespaces.Namespace controller =
            new CommitNamespaces.Namespace("controller", 3L, 0L, CommitNamespaces.NO_THREAD);
        private final RangeSealTable table = new RangeSealTable(16);
        private final EffectStore effects = new EffectStore(4096, 16);
        private final CommitLog log;
        private final TypedConsumerSettlement settlement;
        private final PipelineWriteClaims claims;
        private final JobIntake intake = new JobIntake(() -> 16);
        private final PipelineBatchLedger batches = new PipelineBatchLedger();
        private final PipelineRoundJobs jobs = new PipelineRoundJobs();
        private String sealNodeKey = "";
        private int sealPosition = -1;
        private long epoch;

        private Stage() {
            log = new CommitLog(() -> 8, orderSource(), effects, new SealSource());
            settlement = new TypedConsumerSettlement(() -> log, table, () -> controller);
            claims = new PipelineWriteClaims(versions, guard.worldEpochs(), () -> true,
                world -> LEVEL, attributed -> {
                    payloads.add(attributed);
                    if (settleOnReport) {
                        settlement.settle(attributed);
                    }
                });
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.claimLandedTap((slot, world, tick, row) -> claims.noteLanded(slot, world, row));
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD));
            versions.refresh(true);
            slots.refresh(true);
            grants.refresh(true);
            claims.table(slots);
            claims.owners(owners);
            claims.ledger(batches);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
            epoch = guard.worldEpochs().epochOf(WORLD);
        }

        private CommitLog.OrderSource orderSource() {
            return new CommitLog.OrderSource() {
                @Override
                public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                    return sealNodeKey.isEmpty() || !nodeKey.equals(sealNodeKey) ? null
                        : new CommitLog.Resolved(PLAN_SEQUENCE, sealPosition, false);
                }

                @Override
                public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                                    String domainId, String nodeKey) {
                    return planSequence == PLAN_SEQUENCE ? resolve(worldId, domainId, nodeKey) : null;
                }
            };
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

        private void write(int x, int y, int z) {
            assertEquals(PrtsWorldWriteTaps.BlockWriteTap.JUDGE,
                guard.classifyBlockWrite(LEVEL, x, y, z),
                () -> "claim refusal=" + slots.lastRefusal());
            assertTrue(guard.admitBlockWrite(LEVEL, WORLD, null),
                "the judged path lets the claimed write through");
            assertEquals(WriteDisposition.GRANT, guard.lastDecision().disposition(),
                () -> "the judged path refused: " + guard.lastDecision().code());
        }

        private void report() {
            log.beginTick(TICK);
            assertEquals(1, claims.reportLanded(reports::add), "the span reports its one batch");
            assertFalse(reports.isEmpty());
        }

        private PipelineWriteClaims.Attributed drain() {
            log.beginTick(TICK);
            settleOnReport = false;
            try {
                assertTrue(payloads.isEmpty(), "the case that drains a payload settles it itself");
                assertEquals(1, claims.reportLanded(reports::add));
                return payloads.get(0);
            } finally {
                settleOnReport = true;
            }
        }

        private void declare(long tasks) {
            jobs.bind(batches);
            for (long task = 0; task < tasks; task++) {
                batches.mint(MAILBOX, WORLD, MAILBOX, CELL, TICK);
            }
            jobs.declareInto(intake, PLAN_SEQUENCE, TICK);
            JobGraphBuilder.Freeze frozen =
                JobGraphBuilder.freeze(intake.take(), TICK, PLAN_SEQUENCE, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code() + " "
                + frozen.item());
            JobGraph graph = publish(frozen.graph());
            sealNodeKey = graph.nodes().get(0).key();
            sealPosition = graph.nodes().get(0).position();
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

        private long nextInstance = 41L;
        private long lastInstance;

        private void publishSeal(long cell) {
            lastInstance = nextInstance++;
            RangeSealFixture fixture = new RangeSealFixture();
            fixture.world = WORLD;
            fixture.domain = DOMAIN;
            fixture.planSequence = PLAN_SEQUENCE;
            fixture.position = sealPosition;
            fixture.nodeKey = sealNodeKey;
            fixture.cells = new long[] {cell};
            fixture.cell = cell;
            fixture.status = "full";
            fixture.instanceId = lastInstance;
            fixture.worldEpoch = epoch;
            fixture.freeze = new FrozenDeclarations.FreezeKey(controller.lifecycleKey(), 1L);
            assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(fixture.build()));
        }

        private final class SealSource implements IdentityWitness.Source {

            @Override
            public FrozenDeclarations declarations(String worldId, String domainId,
                                                   long planSequence) {
                return FrozenDeclarations.none();
            }

            @Override
            public long liveWorldEpoch(String worldId) {
                return epoch;
            }

            @Override
            public IdentityWitness.Authorization liveAuthorization(long planSequence,
                    CommitNamespaces.Namespace planController, String worldId, String domainId,
                    String nodeKey) {
                return IdentityWitness.Authorization.refused("", 0L,
                    IdentityWitness.Authorization.Face.notRead(), "not-read-here");
            }

            @Override
            public CommitNamespaces.Namespace currentController() {
                return controller;
            }

            @Override
            public FrozenDeclarations.RangeSeal range(FrozenDeclarations.Seat seat, long cell) {
                return table.locate(seat, cell);
            }

            @Override
            public boolean hasRange(FrozenDeclarations.Seat seat) {
                return table.hasSeat(seat);
            }

            @Override
            public IdentityWitness.Obligation oblige(FrozenDeclarations.RangeSeal seal) {
                return table.oblige(seal);
            }
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
                return step;
            }
        }
    }
}
