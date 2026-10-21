/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.PipelineWriteClaims;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Slot;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.SlotKey;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The generation a row wears and the generation its own plan's grant answered: accepted when the
 * two are one, refused for the version when they are not, unclaimed when the plan authorized
 * nothing, and refused when another plan has taken the source. Every case a refusal is driven
 * through is built so that the live slot carries the very number the row wears and the owner token
 * holds it too: the terms that already existed cannot tell such a row from a legal one, so only the
 * plan's own grant can be the reason it is refused. */
class GrantedGenerationBindingTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String PATH = WritePath.BLOCK_WRITE.key();
    private static final String MAILBOX = "sorter";
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + MAILBOX;
    private static final String SITE = "site:worker";
    private static final long PLAN = 7L;
    private static final long LATER_PLAN = 8L;
    private static final long TICK = 100L;
    private static final long CELL = net.minecraft.world.level.ChunkPos.asLong(0, 0);
    private static final long GENERATION = 1L;
    private static final Object LEVEL = new Object();
    private static final WriteSpace SPACE =
        WriteSpace.of(CellKind.SECTION, SectionPos.asLong(0, 4, 0));
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);

    /** One: the row wears the generation the plan's own grant answered, and the write is captured. */
    @Test
    void aRowWearingTheGenerationItsOwnPlanGrantedIsAccepted() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long granted = scratch.publish(PLAN, graph);
        scratch.grantRights(graph);

        scratch.claims.declare(graph, PLAN, TICK);
        assertEquals(1, scratch.claims.active(), "the plan authorized the declared write set");
        assertEquals(0L, scratch.claims.noVersionCount());
        assertEquals(granted, scratch.claims.claims().get(0).expectedVersion(),
            "the claim row wears the number the plan's own grant answered");

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);

        assertEquals(Verdict.CAPTURED, scratch.judge());
        assertNotNull(scratch.tables.lastCoveredSlot(), "the row of the plan covered the write");
        assertEquals(granted, scratch.tables.lastCoveredSlot().expectedVersion());
        assertNull(scratch.tables.lastRefusal());
    }

    /** Two: the plan granted one generation and the row wears the one its own side read after the
     * slot moved, while the live slot and the owner token both carry that same number. */
    @Test
    void aRowWearingAGenerationItsOwnPlanDidNotGrantIsRefusedForTheVersion() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long granted = scratch.publish(PLAN, graph);
        scratch.versions.advance(WORLD, WriteLevel.REGION, DOMAIN);
        long readByItsOwnSide = scratch.versions.carried(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(granted, readByItsOwnSide, "the slot left the value the plan granted");

        scratch.seat(PLAN, readByItsOwnSide, readByItsOwnSide);

        assertEquals(granted, scratch.versions.grantedFor(PLAN, WORLD, WriteLevel.REGION, DOMAIN),
            "the plan's own grant still answers the number it authorized");
        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
        assertEquals(readByItsOwnSide, scratch.tables.lastRefusal().expectedVersion());
        assertEquals(readByItsOwnSide, scratch.tables.lastRefusal().carriedVersion(),
            "the slot carries the very number the row wears, and the plan granted another one");
    }

    /** Three: this plan authorized nothing for the node, while the slot still carries a value its
     * own side can read and report. */
    @Test
    void aPlanThatAuthorizedNothingLeavesTheWriteUnclaimed() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long readByItsOwnSide = scratch.versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(WriteVersionSlots.NOT_CARRIED, readByItsOwnSide);
        assertNull(scratch.versions.grantedFor(PLAN, WORLD, WriteLevel.REGION, DOMAIN),
            "the grant was taken for no plan, so no plan authorized it");

        scratch.claims.declare(graph, PLAN, TICK);

        assertEquals(1L, scratch.claims.noVersionCount(), "no grant of this plan covered the node");
        assertEquals(0, scratch.claims.active());
        assertEquals(0L, scratch.versions.grantedPlan(), "no plan published a record");
        assertEquals(Verdict.DEFAULT_PATH, scratch.judge());

        scratch.seat(PLAN, readByItsOwnSide, readByItsOwnSide);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
        assertEquals(readByItsOwnSide, scratch.tables.lastRefusal().expectedVersion());
        assertEquals(readByItsOwnSide, scratch.tables.lastRefusal().carriedVersion());
    }

    /** Three, second reading: a plan whose grant answered the declared absence is a record, and a
     * record is not the same reading as no plan having granted at all. */
    @Test
    void aPlanWhoseGrantAnsweredTheAbsenceIsReadAwayFromNoGrantAtAll() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long granted = scratch.publish(PLAN, graph);
        assertNotEquals(WriteVersionSlots.NOT_CARRIED, granted);
        assertEquals(PLAN, scratch.versions.grantedPlan());
        assertNotNull(scratch.versions.grantedFor(PLAN, WORLD, WriteLevel.REGION, DOMAIN));
        assertNull(scratch.versions.grantedFor(LATER_PLAN, WORLD, WriteLevel.REGION, DOMAIN),
            "another plan is not the plan the record belongs to");

        scratch.versions.refresh(false);
        long absence = scratch.publish(LATER_PLAN, graph);

        assertEquals(WriteVersionSlots.NOT_CARRIED, absence, "the grant answered the absence");
        assertEquals(LATER_PLAN, scratch.versions.grantedPlan(),
            "a record was published, so the absence is a value and not a missing one");
        assertEquals((Long) WriteVersionSlots.NOT_CARRIED,
            scratch.versions.grantedFor(LATER_PLAN, WORLD, WriteLevel.REGION, DOMAIN));

        scratch.seat(LATER_PLAN, WriteVersionSlots.NOT_CARRIED, 0L);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
        assertEquals(WriteVersionSlots.NOT_CARRIED,
            scratch.tables.lastRefusal().expectedVersion());
    }

    /** Four: another plan has taken the source without the generation moving, so the row of the
     * earlier plan wears a number the record's own value still equals. */
    @Test
    void aRowOfAPlanTheSourceNoLongerHoldsIsRefusedForTheVersion() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long granted = scratch.publish(PLAN, graph);
        long renewed = scratch.publish(LATER_PLAN, graph);
        assertEquals(granted, renewed, "nothing moved, so the later plan granted the same value");
        assertNull(scratch.versions.grantedFor(PLAN, WORLD, WriteLevel.REGION, DOMAIN),
            "the record belongs to the later plan");

        scratch.seat(PLAN, granted, granted);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
        assertEquals(granted, scratch.tables.lastRefusal().expectedVersion());
        assertEquals(granted, scratch.tables.lastRefusal().carriedVersion());
    }

    /** Four, second reading: the generation itself was retired under the plan that replaced it. */
    @Test
    void aRowWearingTheRetiredGenerationUnderItsOwnPlanIsRefusedForTheVersion() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        long granted = scratch.publish(PLAN, graph);
        scratch.versions.advance(WORLD, WriteLevel.REGION, DOMAIN);
        long renewed = scratch.publish(LATER_PLAN, graph);
        assertNotEquals(granted, renewed, "the later plan authorized the renewed generation");

        scratch.seat(PLAN, granted, renewed);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
        assertEquals(granted, scratch.tables.lastRefusal().expectedVersion());
        assertEquals(renewed, scratch.tables.lastRefusal().carriedVersion());
    }

    /** The table, the claim point and the slot source of one process, wired the way the kernel wires
     * them; only the world of a level and the step of the plan are fixtures. */
    private static final class Scratch {

        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final WorldEpochs epochs = new WorldEpochs();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final OwnerGrantPoint rights = new OwnerGrantPoint(owners, versions);
        private final WriteControlledSlots tables =
            new WriteControlledSlots(owners, versions, epochs, new FixtureSource());
        private final PipelineWriteClaims claims =
            new PipelineWriteClaims(versions, epochs, () -> true, world -> LEVEL);
        private final JobIntake intake = new JobIntake(() -> 16);
        private final PipelineBatchLedger batches = new PipelineBatchLedger();
        private final PipelineRoundJobs jobs = new PipelineRoundJobs();

        private Scratch() {
            versions.refresh(true);
            epochs.observe(List.of(WORLD));
            rights.refresh(true);
            tables.refresh(true);
            claims.table(tables);
            claims.owners(owners);
            claims.ledger(batches);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
        }

        private JobGraph declare() {
            jobs.bind(batches);
            for (long task = 0; task < 5L; task++) {
                batches.mint(MAILBOX, WORLD, MAILBOX, CELL, TICK);
            }
            jobs.declareInto(intake, PLAN, TICK);
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(intake.take(), TICK, PLAN, 1L, 16);
            assertEquals("", frozen.ok() ? "" : frozen.code(), "the fixture plan froze");
            return frozen.graph();
        }

        /** The plan point: every declared domain is granted and the answer of each grant is
         * published as this plan's own record before anything of the plan is handed out. */
        private long publish(long planSequence, JobGraph graph) {
            Map<OwnershipDomain, Long> granted = new HashMap<>();
            for (JobGraph.Node node : graph.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    OwnershipDomain domain =
                        new OwnershipDomain(ref.worldId(), WriteLevel.REGION, ref.domainId());
                    granted.put(domain,
                        versions.grant(ref.worldId(), WriteLevel.REGION, ref.domainId()));
                }
            }
            versions.noteGranted(planSequence, granted);
            return granted.get(new OwnershipDomain(WORLD, WriteLevel.REGION, DOMAIN));
        }

        /** The owner grant point of the same freeze: the right of the plan's own demand. */
        private void grantRights(JobGraph graph) {
            rights.apply(graph, TICK);
        }

        /** One row of one plan's seat, wearing the number its own side reports, with the owner token
         * holding that same number: nothing but the version can be the reason it is refused. */
        private void seat(long planSequence, long expectedVersion, long tokenVersion) {
            owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, tokenVersion,
                0L, 0L, HolderKind.REGISTERED, SITE));
            Slot slot = new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION,
                SlotKey.segmentOf(SPACE), planSequence), LEVEL, NODE_KEY, 0, expectedVersion,
                GENERATION, epochs.epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, SITE,
                Set.of(PATH), SPACE);
            assertEquals(WriteControlledSlots.Refusal.NONE, tables.register(slot));
        }

        private Verdict judge() {
            return tables.judge(LEVEL, PATH, WITNESS, TICK).verdict();
        }
    }

    /** Which world a level is and which step the plan holds for a claim. */
    private static final class FixtureSource implements WriteControlledSlots.Source {

        @Override
        public String worldOf(Object levelRef) {
            return levelRef == LEVEL ? WORLD : "";
        }

        @Override
        public Step stepOf(String worldId, String domainId, String nodeKey) {
            return nodeKey.startsWith(NODE_KEY) ? new Step(PLAN, 0) : null;
        }
    }
}
