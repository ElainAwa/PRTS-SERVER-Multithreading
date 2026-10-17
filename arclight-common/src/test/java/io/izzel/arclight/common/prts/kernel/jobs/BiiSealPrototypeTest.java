/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V0 to V6 of the B-ii contract, run on cuts the real {@link PipelineBatchLedger} made. Every case
 * has a positive and a negative: the negative is the same fixture with one input made wrong, and it
 * has to be refused with the named code, not repaired into a range. */
class BiiSealPrototypeTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final long PLAN = 7L;
    private static final long TICK = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final long OTHER = ChunkPos.asLong(9, 9);
    private static final String STATUS = "features";
    private static final String DOMAIN = PipelineRoundJobs.DOMAIN;
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey("lifecycle-323", 1L);

    private static PipelineBatchLedger.BatchId freeze(PipelineBatchLedger ledger, String mailbox,
                                                      long... cells) {
        for (long cell : cells) {
            ledger.mint(new Object(), WORLD, mailbox, cell, TICK);
        }
        return ledger.freeze(WORLD, mailbox, PLAN, TICK);
    }

    private static long[] cells16() {
        long[] cells = new long[16];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = ChunkPos.asLong(index, 0);
        }
        return cells;
    }

    private static BiiSealPrototype.FreezePoint point(long epoch) {
        return new BiiSealPrototype.FreezePoint(world -> epoch);
    }

    private static BiiSealPrototype.Seal seal(BiiSealPrototype.FreezePoint point,
                                              PipelineBatchLedger.BatchId batch) {
        return new BiiSealPrototype.Seal(point, FREEZE, PLAN, List.of(
            new FrozenDeclarations.Assignment(WORLD, DOMAIN,
                PipelineRoundJobs.batchKeyOf(batch), 0)));
    }

    private static BiiSealPrototype.Node identity(BiiSealPrototype.Seal seal,
                                                  PipelineBatchLedger.BatchId batch) {
        BiiSealPrototype.Outcome outcome = seal.identity(batch, DOMAIN,
            PipelineRoundJobs.batchKeyOf(batch), 0);
        assertEquals(BiiSealPrototype.Code.DECLARED, outcome.code());
        return outcome.node();
    }

    @Test
    void v0PositiveOneCellCutSealsTheRangeOfThatBatch() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.FreezePoint point = point(1L);
        BiiSealPrototype.Seal seal = seal(point, batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 99L);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertTrue(outcome.rangeDeclared());
        assertNotNull(outcome.sealed());
        assertEquals(JobDeclaration.RangeGranularity.CHUNK, outcome.range().granularity());
        assertArrayEquals(new long[] {CELL}, outcome.range().cells());
        assertEquals(1L, outcome.sealed().instanceId());
        assertEquals(1L, outcome.sealed().worldEpoch());
        assertEquals(1L, point.minted());
        assertFalse(outcome.sealed().space().digest().isEmpty());
        seal.firstWrite(node);
        assertTrue(seal.witness(outcome.sealed(), CELL, STATUS, 1L, 1L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
    }

    @Test
    void v0PositiveSixteenCellCutSealsEveryCellOfTheCut() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        long[] cells = cells16();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, cells);
        BiiSealPrototype.Seal seal = seal(point(4L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch),
            cells[15], STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertEquals(16, outcome.range().cells().length);
        assertEquals(16, outcome.sealed().space().size());
        assertArrayEquals(cells, outcome.range().cells());
        assertTrue(seal.witness(outcome.sealed(), cells[0], STATUS, 1L, 4L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
        assertTrue(seal.witness(outcome.sealed(), cells[15], STATUS, 1L, 4L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
        assertFalse(seal.witness(outcome.sealed(), ChunkPos.asLong(99, 0), STATUS, 1L, 4L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
    }

    @Test
    void v0NegativePartialRangeIsRefusedInsteadOfBecomingTheBatch() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        long[] cells = cells16();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, cells);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, Arrays.copyOf(cells, 15),
            cells[0], STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.RANGE_NOT_OF_BATCH, outcome.code());
        assertNull(outcome.range());
        assertNull(outcome.sealed());
    }

    @Test
    void v1PositiveNodeIdentityIsSealedAtTheFreeze() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);

        BiiSealPrototype.Outcome outcome = seal.identity(batch, DOMAIN,
            PipelineRoundJobs.batchKeyOf(batch), 0);

        assertEquals(BiiSealPrototype.Code.DECLARED, outcome.code());
        assertNotNull(outcome.node());
        assertEquals(WORLD, outcome.node().batch().worldId());
        assertEquals(MAILBOX, outcome.node().batch().mailbox());
        assertEquals(PLAN, outcome.node().planSequence());
        assertEquals(FREEZE, outcome.node().freeze());
    }

    @Test
    void v1NegativeMissingIdentityFieldsAreRefused() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        PipelineBatchLedger.BatchId noWorld = new PipelineBatchLedger.BatchId("", MAILBOX, 1L, PLAN);
        PipelineBatchLedger.BatchId noPlan = new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 1L, 0L);
        String key = PipelineRoundJobs.batchKeyOf(batch);

        assertEquals(BiiSealPrototype.Code.IDENTITY_MISSING,
            seal.identity(noWorld, DOMAIN, PipelineRoundJobs.batchKeyOf(noWorld), 0).code());
        assertEquals(BiiSealPrototype.Code.IDENTITY_MISSING,
            seal.identity(batch, "", key, 0).code());
        assertEquals(BiiSealPrototype.Code.IDENTITY_MISSING,
            seal.identity(batch, DOMAIN, "", 0).code());
        assertEquals(BiiSealPrototype.Code.PLAN_INVALID,
            seal.identity(noPlan, DOMAIN, key, 0).code());
        assertEquals(BiiSealPrototype.Code.NODE_KEY_MISMATCH,
            seal.identity(batch, DOMAIN, "wrong-node", 0).code());
        assertEquals(BiiSealPrototype.Code.SEAT_MISSING,
            seal.identity(batch, DOMAIN, key, 1).code());
        BiiSealPrototype.Seal noFreeze = new BiiSealPrototype.Seal(point(1L),
            FrozenDeclarations.FreezeKey.NONE, PLAN, List.of(new FrozenDeclarations.Assignment(
                WORLD, DOMAIN, key, 0)));
        assertEquals(BiiSealPrototype.Code.FREEZE_MISSING,
            noFreeze.identity(batch, DOMAIN, key, 0).code());
        assertEquals(BiiSealPrototype.Code.DECLARED, seal.identity(batch, DOMAIN, key, 0).code());
        assertEquals(BiiSealPrototype.Code.DUPLICATE_NODE,
            seal.identity(batch, DOMAIN, key, 0).code());
    }

    @Test
    void v2PositiveOneCellRangeIsNotEmpty() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertEquals(1, outcome.range().cells().length);
    }

    @Test
    void v2NegativeEmptyOrNullRangeIsRefused() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome empty = seal.range(node, ledger, new long[0], CELL, STATUS, 0L);
        BiiSealPrototype.Outcome absent = seal.range(node, ledger, null, CELL, STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.RANGE_EMPTY, empty.code());
        assertEquals(BiiSealPrototype.Code.RANGE_EMPTY, absent.code());
        assertNull(empty.range());
        assertNull(absent.range());
        assertNull(empty.sealed());
        assertNull(absent.sealed());
    }

    @Test
    void v3PositiveSixteenCellsStayInsideTheDeclaredBound() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, cells16());
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch),
            ledger.cellsOf(batch)[0], STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertEquals(16, outcome.range().cells().length);
        assertTrue(outcome.sealed().space().wellFormed());
    }

    @Test
    void v3NegativeSeventeenCellsAreRefusedAndNotTruncated() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        long[] cells = cells16();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, cells);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        long[] seventeen = Arrays.copyOf(cells, 17);
        seventeen[16] = ChunkPos.asLong(16, 0);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, seventeen, cells[0], STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.RANGE_TOO_WIDE, outcome.code());
        assertNull(outcome.range());
        assertNull(outcome.sealed());
    }

    @Test
    void v4PositiveOnlyTheBatchOfTheNodeIsSealed() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL, OTHER);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertArrayEquals(new long[] {CELL, OTHER}, outcome.range().cells());
    }

    @Test
    void v4NegativeCellsOfAnotherBatchAreRefused() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL, OTHER);
        long otherCell = ChunkPos.asLong(3, 3);
        PipelineBatchLedger.BatchId other = freeze(ledger, "light", otherCell);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(other), otherCell,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.RANGE_NOT_OF_BATCH, outcome.code());
        assertNull(outcome.range());
        assertNull(outcome.sealed());
    }

    @Test
    void v4NegativeTheRunningTaskCellHasToBeInsideTheRange() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL, OTHER);
        long otherCell = ChunkPos.asLong(3, 3);
        freeze(ledger, "light", otherCell);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), otherCell,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.OWN_CELL_OUTSIDE, outcome.code());
        assertNull(outcome.range());
        assertNull(outcome.sealed());
    }

    @Test
    void v4NegativeALineOutsideTheSealedRangeIsRefused() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);

        BiiSealPrototype.Verdict outside = seal.witness(outcome.sealed(), OTHER, STATUS, 1L, 1L,
            PLAN, 0, PipelineRoundJobs.batchKeyOf(batch));
        BiiSealPrototype.Verdict inside = seal.witness(outcome.sealed(), CELL, STATUS, 1L, 1L,
            PLAN, 0, PipelineRoundJobs.batchKeyOf(batch));

        assertFalse(outside.accepted());
        assertEquals("write_outside_range", outside.reason());
        assertTrue(inside.accepted());
    }

    @Test
    void v5PositiveRangeIsSealedBeforeTheFirstWrite() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(2L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);
        seal.firstWrite(node);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertTrue(seal.witness(outcome.sealed(), CELL, STATUS, 1L, 2L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
    }

    @Test
    void v5NegativeARangeSealedAfterTheFirstWriteIsLate() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        seal.firstWrite(node);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.LATE, outcome.code());
        assertNull(outcome.range());
        assertNull(outcome.sealed());
    }

    @Test
    void v5NegativeARangeOfAnotherFreezeIsNotRecognized() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal first = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(first, batch);
        BiiSealPrototype.Outcome sealed = first.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);
        BiiSealPrototype.Seal next = new BiiSealPrototype.Seal(point(1L), FREEZE.next(), PLAN,
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN,
                PipelineRoundJobs.batchKeyOf(batch), 0)));

        BiiSealPrototype.Outcome stale = next.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);
        assertFalse(first.witness(sealed.sealed(), CELL, STATUS, 1L, 1L, PLAN + 1L, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());

        assertEquals(BiiSealPrototype.Code.IDENTITY_MISSING, stale.code());
        assertNull(stale.range());
    }

    @Test
    void v6PositiveCompleteArtefactSealsAndIgnoresTheProducerNumber() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.FreezePoint point = point(1L);
        BiiSealPrototype.Seal seal = seal(point, batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        long producerNumber = 99L;

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, producerNumber);

        assertEquals(BiiSealPrototype.Code.SEALED, outcome.code());
        assertEquals(1L, outcome.sealed().instanceId());
        assertNotEquals(producerNumber, outcome.sealed().instanceId());
        assertEquals(1L, point.minted());
        assertTrue(seal.witness(outcome.sealed(), CELL, STATUS, 1L, 1L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
        assertFalse(seal.witness(outcome.sealed(), CELL, STATUS, producerNumber, 1L, PLAN, 0,
            PipelineRoundJobs.batchKeyOf(batch)).accepted());
    }

    @Test
    void v6NegativeMissingStatusLeavesTheRangeWithoutASeal() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.FreezePoint point = point(1L);
        BiiSealPrototype.Seal seal = seal(point, batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            "", 0L);

        assertEquals(BiiSealPrototype.Code.STATUS_MISSING, outcome.code());
        assertTrue(outcome.rangeDeclared());
        assertNull(outcome.sealed());
        assertEquals(0L, point.minted());
    }

    @Test
    void v6NegativeADeadWorldEpochMintsNothing() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.FreezePoint point = point(0L);
        BiiSealPrototype.Seal seal = seal(point, batch);
        BiiSealPrototype.Node node = identity(seal, batch);

        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);

        assertEquals(BiiSealPrototype.Code.WORLD_NOT_LIVE, outcome.code());
        assertTrue(outcome.rangeDeclared());
        assertNull(outcome.sealed());
        assertEquals(0L, point.minted());
    }

    @Test
    void v6NegativeASpaceThatDoesNotRecomputeIsRefused() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);
        JobDeclaration.WriteRange forgedRange = new JobDeclaration.WriteRange(
            JobDeclaration.RangeGranularity.CHUNK, new long[] {OTHER});
        BiiSealPrototype.Sealed forged = new BiiSealPrototype.Sealed(outcome.sealed().node(),
            forgedRange, outcome.sealed().space(), STATUS, 1L, 1L);

        BiiSealPrototype.Verdict verdict = BiiSealPrototype.verifyEntry(forged, CELL, STATUS, 1L,
            1L, PLAN, 0, PipelineRoundJobs.batchKeyOf(batch));

        assertFalse(verdict.accepted());
        assertEquals("space_mismatch", verdict.reason());
    }

    @Test
    void v6NegativeWitnessColumnsHaveToComeFromTheSameSeal() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineBatchLedger.BatchId batch = freeze(ledger, MAILBOX, CELL);
        BiiSealPrototype.Seal seal = seal(point(1L), batch);
        BiiSealPrototype.Node node = identity(seal, batch);
        BiiSealPrototype.Outcome outcome = seal.range(node, ledger, ledger.cellsOf(batch), CELL,
            STATUS, 0L);
        String key = PipelineRoundJobs.batchKeyOf(batch);

        assertEquals("status_mismatch", seal.witness(outcome.sealed(), CELL, "other", 1L, 1L,
            PLAN, 0, key).reason());
        assertEquals("instance_mismatch", seal.witness(outcome.sealed(), CELL, STATUS, 99L, 1L,
            PLAN, 0, key).reason());
        assertEquals("epoch_mismatch", seal.witness(outcome.sealed(), CELL, STATUS, 1L, 9L,
            PLAN, 0, key).reason());
        assertEquals("seat_mismatch", seal.witness(outcome.sealed(), CELL, STATUS, 1L, 1L,
            PLAN, 1, key).reason());
    }
}
