/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.PipelineWriteClaims;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** One probe, one body of bytes, run in the tree before the plan owns a generation and in the tree
 * after it: the reading it asserts is the one the planning period's own grant has to be the source
 * of. Before, the generation a row wears is the value its own request side read for itself, and a
 * caller that reports one number in both places is accepted; after, a row wearing a generation no
 * grant of its plan answered is a version it does not carry. The file names no type the older tree
 * does not have, so the same bytes compile and run in both.
 *
 * <p>Only the version terms are driven: the world and the step are answered by the fixture, and the
 * owner token is acquired with the very number the row wears, so nothing but the version can be the
 * reason a write is refused. */
class GrantedGenerationProbeTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String PATH = WritePath.BLOCK_WRITE.key();
    private static final String MAILBOX = "sorter";
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + MAILBOX;
    private static final String SITE = "site:worker";
    private static final long PLAN = 7L;
    private static final long TICK = 100L;
    private static final long CELL = net.minecraft.world.level.ChunkPos.asLong(0, 0);
    private static final long GENERATION = 1L;
    private static final Object LEVEL = new Object();
    private static final WriteSpace SPACE =
        WriteSpace.of(CellKind.SECTION, SectionPos.asLong(0, 4, 0));
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);

    @Test
    void aRowWearingTheGenerationItsOwnRequestSideReadIsRefusedForTheVersion() {
        Scratch scratch = new Scratch();
        long granted = scratch.versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        scratch.versions.advance(WORLD, WriteLevel.REGION, DOMAIN);
        long readByItsOwnSide = scratch.versions.carried(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(granted, readByItsOwnSide, "the slot left the value the grant answered");

        scratch.seat(readByItsOwnSide);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
    }

    @Test
    void aDeclaredWriteSetNoPlanAuthorizedIsLeftUnclaimed() {
        Scratch scratch = new Scratch();
        JobGraph graph = scratch.declare();
        scratch.versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        scratch.versions.advance(WORLD, WriteLevel.REGION, DOMAIN);
        scratch.claims.declare(graph, PLAN, TICK);

        assertEquals(1L, scratch.claims.noVersionCount());
        assertEquals(0, scratch.claims.active());
        assertEquals(Verdict.DEFAULT_PATH, scratch.judge());
        assertEquals(0L, scratch.tables.rejectedCount());
    }

    @Test
    void aRowWhoseLiveValueMovedPastTheGrantItWearsIsRefusedForTheVersion() {
        Scratch scratch = new Scratch();
        long granted = scratch.versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        scratch.versions.advance(WORLD, WriteLevel.REGION, DOMAIN);

        scratch.seat(granted);

        assertEquals(Verdict.REJECTED, scratch.judge());
        assertEquals("version", scratch.tables.lastRefusal().reason());
    }

    /** The table, the claim point and the slot source of one process, wired the way the kernel wires
     * them; only the world of a level and the step of the plan are fixtures. */
    private static final class Scratch {

        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final WorldEpochs epochs = new WorldEpochs();
        private final OwnerRegistry owners = new OwnerRegistry();
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
            tables.refresh(true);
            claims.table(tables);
            claims.ledger(batches);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
        }

        /** The declarations the chunk pipeline hands in, frozen into the plan this fixture stands
         * for: the version the plan point grants is taken before the claim of the plan is declared. */
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

        /** One row of the plan's own seat, wearing the number the caller hands in, with the owner
         * token holding that same number: what a caller reports twice is what the row is judged by. */
        private void seat(long expectedVersion) {
            owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION,
                expectedVersion, 0L, 0L, HolderKind.REGISTERED, SITE));
            Slot slot = new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION,
                SlotKey.segmentOf(SPACE), PLAN), LEVEL, NODE_KEY, 0, expectedVersion, GENERATION,
                epochs.epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, SITE, Set.of(PATH), SPACE);
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
            return nodeKey.equals(NODE_KEY) ? new Step(PLAN, 0) : null;
        }
    }
}
