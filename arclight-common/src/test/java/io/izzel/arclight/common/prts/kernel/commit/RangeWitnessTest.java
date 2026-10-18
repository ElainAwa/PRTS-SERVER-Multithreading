/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The range witness: a line of a sealed cell is judged by the range that covers it and by the one
 * status the seal carries for that cell, with both folds recomputed. Every seal here is the real
 * immutable record and every judgement the real witness. */
class RangeWitnessTest {

    private static final String WORLD = RangeSealFixture.WORLD;
    private static final String DOMAIN = RangeSealFixture.DOMAIN;
    private static final String NODE = "chunk-pipeline/" + WORLD + "/" + RangeSealFixture.MAILBOX
        + "#" + RangeSealFixture.FREEZE_ORDINAL;
    private static final long LIFETIME = 4242L;
    private static final long AUTHORIZATION = 3L;
    private static final long EFFECT = 100L;

    private static CommitLog.OrderSource order(int position) {
        return (worldId, domainId, nodeKey) -> WORLD.equals(worldId) && DOMAIN.equals(domainId)
            && NODE.equals(nodeKey) ? new CommitLog.Resolved(RangeSealFixture.PLAN, position, false)
            : null;
    }

    private static CommitLog.Identity identity(FrozenDeclarations.RangeSeal seal, long cell,
                                               String status) {
        return identity(seal.instanceId(), seal.worldEpoch(), cell, status, seal.planSequence(),
            seal.seat().position());
    }

    private static CommitLog.Identity identity(long instanceId, long worldEpoch, long cell,
                                               String status, long plan, int position) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instanceId, worldEpoch, cell, status,
            AUTHORIZATION, EFFECT, new CommitNamespaces.Namespace("producer", LIFETIME, 0L,
                CommitNamespaces.NO_THREAD), new CommitNamespaces.Namespace("controller", LIFETIME,
                0L, CommitNamespaces.NO_THREAD));
    }

    private static CommitLog.Resolved resolved(FrozenDeclarations.RangeSeal seal) {
        return new CommitLog.Resolved(seal.planSequence(), seal.seat().position(), false);
    }

    private static IdentityWitness.Verdict witness(FrozenDeclarations.RangeSeal seal,
                                                   CommitLog.Identity identity) {
        return IdentityWitness.verifyRange(seal, resolved(seal), WORLD, DOMAIN, NODE, identity,
            null);
    }

    private static FrozenDeclarations.RangeSeal rebuild(FrozenDeclarations.RangeSeal seal,
                                                        FrozenDeclarations.Stamp stamp) {
        return new FrozenDeclarations.RangeSeal(seal.seat(), seal.nodeKey(), seal.role(),
            seal.worldId(), seal.mailbox(), seal.freezeOrdinal(), seal.planSequence(), seal.freeze(),
            seal.cells(), seal.spaceDigest(), seal.statuses(), seal.statusMapDigest(),
            seal.instanceId(), seal.worldEpoch(), stamp);
    }

    @Test
    void theLineOfTheSealedCellIsWitnessedByAnI2Key() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        IdentityWitness.Verdict verdict = witness(seal, identity(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS));

        assertTrue(verdict.accepted());
        assertTrue(verdict.instance().startsWith("I2"));
        assertEquals(CommitLog.logicalKeyOf(seal).canonical(), verdict.instance());
        IdentityWitness.LogicalKey parsed = IdentityWitness.parse(verdict.instance());
        assertNotNull(parsed);
        assertTrue(parsed.ranged());
        assertEquals(verdict.instance(), parsed.canonical());
        assertEquals(RangeSealFixture.CELL,
            parsed.canonical().equals(CommitLog.logicalKeyOf(seal).canonical()) ? RangeSealFixture.CELL
                : -1L);
    }

    @Test
    void theLastCellOfASixteenCellRangeIsWitnessed() {
        long[] cells = new long[16];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = RangeSealFixture.CELL + index;
        }
        long last = cells[cells.length - 1];
        RangeSealFixture fixture = new RangeSealFixture();
        fixture.cells(cells).cell(last);
        FrozenDeclarations.RangeSeal seal = fixture.build();
        assertTrue(seal.wellFormed());
        assertTrue(witness(seal, identity(seal, last, RangeSealFixture.STATUS)).accepted());
    }

    @Test
    void aCellOutsideTheRangeIsRefusedAsAChunkMismatch() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        IdentityWitness.Verdict verdict = witness(seal,
            identity(seal, RangeSealFixture.CELL2, RangeSealFixture.STATUS));

        assertFalse(verdict.accepted());
        assertEquals("chunk-witness-mismatch", verdict.reason());
    }

    @Test
    void aCellInsideTheRangeWithoutAStatusIsRefused() {
        RangeSealFixture fixture = new RangeSealFixture();
        fixture.cells(RangeSealFixture.CELL, RangeSealFixture.CELL2).cell(RangeSealFixture.CELL);
        FrozenDeclarations.RangeSeal seal = fixture.build();
        assertTrue(seal.wellFormed());

        IdentityWitness.Verdict verdict = witness(seal,
            identity(seal, RangeSealFixture.CELL2, RangeSealFixture.STATUS));

        assertFalse(verdict.accepted());
        assertEquals("range-status-missing", verdict.reason());
    }

    @Test
    void aStatusThatTheSealDoesNotProveForTheCellIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        IdentityWitness.Verdict verdict = witness(seal, identity(seal, RangeSealFixture.CELL,
            "empty"));

        assertFalse(verdict.accepted());
        assertEquals("declaration-status-mismatch", verdict.reason());
    }

    @Test
    void aTamperedSpaceOrStatusMapFoldIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        FrozenDeclarations.RangeSeal space = new RangeSealFixture()
            .spaceDigest("00000000deadbeef").build();
        FrozenDeclarations.RangeSeal statuses = new RangeSealFixture()
            .statusMapDigest("00000000deadbeef").build();

        IdentityWitness.Verdict spaceVerdict = witness(space,
            identity(space, RangeSealFixture.CELL, RangeSealFixture.STATUS));
        IdentityWitness.Verdict mapVerdict = witness(statuses,
            identity(statuses, RangeSealFixture.CELL, RangeSealFixture.STATUS));

        assertFalse(spaceVerdict.accepted());
        assertEquals("range-digest-mismatch", spaceVerdict.reason());
        assertFalse(mapVerdict.accepted());
        assertEquals("range-digest-mismatch", mapVerdict.reason());
        assertTrue(witness(seal, identity(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS)).accepted());
    }

    @Test
    void anotherInstanceNumberOrWorldGenerationIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        FrozenDeclarations.RangeSeal otherNumber = new RangeSealFixture()
            .instance(RangeSealFixture.INSTANCE + 1L).build();
        FrozenDeclarations.RangeSeal otherEpoch = new RangeSealFixture()
            .epoch(RangeSealFixture.EPOCH + 1L).build();

        IdentityWitness.Verdict instance = witness(seal, identity(RangeSealFixture.INSTANCE + 1L,
            RangeSealFixture.EPOCH, RangeSealFixture.CELL, RangeSealFixture.STATUS,
            RangeSealFixture.PLAN, 4));
        IdentityWitness.Verdict epoch = witness(seal, identity(RangeSealFixture.INSTANCE,
            RangeSealFixture.EPOCH + 1L, RangeSealFixture.CELL, RangeSealFixture.STATUS,
            RangeSealFixture.PLAN, 4));

        assertEquals("declaration-instance-mismatch", instance.reason());
        assertEquals("declaration-epoch-mismatch", epoch.reason());
        assertFalse(witness(otherNumber, identity(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS)).accepted());
        assertFalse(witness(otherEpoch, identity(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS)).accepted());
    }

    @Test
    void aStampOfAnotherCellOrBatchIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        FrozenDeclarations.Stamp moved = new FrozenDeclarations.Stamp(
            seal.stamp().taskOrdinal(), seal.stamp().world(), seal.stamp().mailbox(),
            seal.stamp().cell() + 1L, seal.stamp().mintTick(),
            seal.stamp().batchFreezeOrdinal() + 1L, seal.stamp().planSequence(),
            seal.stamp().demandToken());
        FrozenDeclarations.RangeSeal rebuilt = rebuild(seal, moved);

        IdentityWitness.Verdict verdict = witness(rebuilt, identity(seal, RangeSealFixture.CELL,
            RangeSealFixture.STATUS));

        assertFalse(verdict.accepted());
        assertEquals("range-stamp-mismatch", verdict.reason());
        assertFalse(rebuilt.wellFormed());
    }

    @Test
    void anEmptyOrSeventeenCellSealIsNotWellFormedAndIsRefused() {
        long[] seventeen = new long[17];
        for (int index = 0; index < seventeen.length; index++) {
            seventeen[index] = index + 1L;
        }
        RangeSealFixture empty = new RangeSealFixture();
        empty.cells(new long[0]).cell(0L);
        RangeSealFixture wide = new RangeSealFixture();
        wide.cells(seventeen).cell(1L);
        FrozenDeclarations.RangeSeal emptySeal = empty.build();
        FrozenDeclarations.RangeSeal wideSeal = wide.build();

        assertFalse(emptySeal.wellFormed());
        assertFalse(wideSeal.wellFormed());
        assertEquals("range-digest-mismatch",
            witness(emptySeal, identity(emptySeal, 0L, RangeSealFixture.STATUS)).reason());
        assertEquals("range-digest-mismatch",
            witness(wideSeal, identity(wideSeal, 1L, RangeSealFixture.STATUS)).reason());
        assertFalse(wideSeal.contains(seventeen[16] + 10L));
    }

    @Test
    void anotherSeatPlanOrNodeIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        CommitLog.Identity identity = identity(seal, RangeSealFixture.CELL, RangeSealFixture.STATUS);
        CommitLog.Resolved otherPosition = new CommitLog.Resolved(seal.planSequence(),
            seal.seat().position() + 1, false);
        CommitLog.Resolved otherPlan = new CommitLog.Resolved(seal.planSequence() + 1,
            seal.seat().position(), false);

        assertEquals("range-seat-mismatch", IdentityWitness.verifyRange(seal, otherPosition, WORLD,
            DOMAIN, NODE, identity, null).reason());
        assertEquals("range-seat-mismatch", IdentityWitness.verifyRange(seal, otherPlan, WORLD,
            DOMAIN, NODE, identity, null).reason());
        assertEquals("range-seat-mismatch", IdentityWitness.verifyRange(seal, resolved(seal), WORLD,
            DOMAIN, NODE + "-other", identity, null).reason());
        assertFalse(IdentityWitness.verifyRange(null, resolved(seal), WORLD, DOMAIN, NODE, identity,
            null).accepted());
    }

    @Test
    void aLifecycleTheHolderLeftIsRefused() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        IdentityWitness.Verdict verdict = IdentityWitness.verifyRange(seal, resolved(seal), WORLD,
            DOMAIN, NODE, identity(seal, RangeSealFixture.CELL, RangeSealFixture.STATUS),
            new CommitNamespaces.Namespace("controller", LIFETIME, 0L, CommitNamespaces.NO_THREAD));

        assertFalse(verdict.accepted());
        assertEquals("declaration-lifecycle-mismatch", verdict.reason());
    }

    @Test
    void theStatusMapFoldIgnoresTheInsertionOrderOfItsEntries() {
        Map<Long, FrozenDeclarations.CellStatus> first = new LinkedHashMap<>();
        Map<Long, FrozenDeclarations.CellStatus> second = new LinkedHashMap<>();
        FrozenDeclarations.Stamp stamp = new FrozenDeclarations.Stamp(1L, WORLD,
            RangeSealFixture.MAILBOX, RangeSealFixture.CELL, 100L, RangeSealFixture.FREEZE_ORDINAL,
            RangeSealFixture.PLAN, RangeSealFixture.TOKEN);
        FrozenDeclarations.CellStatus full = new FrozenDeclarations.CellStatus("full", stamp);
        FrozenDeclarations.CellStatus empty = new FrozenDeclarations.CellStatus("empty", stamp);
        first.put(RangeSealFixture.CELL, full);
        first.put(RangeSealFixture.CELL2, empty);
        second.put(RangeSealFixture.CELL2, empty);
        second.put(RangeSealFixture.CELL, full);

        assertEquals(RangeDigests.statusMapDigest(first), RangeDigests.statusMapDigest(second));
        assertNotEquals("", RangeDigests.statusMapDigest(first));
        assertEquals("", RangeDigests.statusMapDigest(Map.of()));
    }

    @Test
    void aRealLogAcceptsTheRangeLineAndKeepsTheFreezeUntilItIsRead() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        EffectStore store = new EffectStore(4096, 16);
        CommitLog log = new CommitLog(() -> 8, order(seal.seat().position()), store,
            new RangeSource(table, FrozenDeclarations.none(), seal.freeze(),
                seal.worldEpoch(), AUTHORIZATION));
        log.beginTick(10L);
        CommitLog.Verdict verdict = log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, AUTHORIZATION,
                11L, 1), identity(seal, RangeSealFixture.CELL, RangeSealFixture.STATUS),
            new EffectStore.Effect(EFFECT, "chunk-materialize", "full",
                List.of("chunk=" + RangeSealFixture.CELL))));

        assertTrue(verdict.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
        assertEquals(1L, log.accepted());
        assertEquals(1L, log.typedStored());
        assertEquals(0L, log.typedRefused());
        assertEquals(1, log.unreadObligations());
        assertEquals(1, table.pinnedCount());
        assertNull(verdict.code());

        assertTrue(table.release(seal));
        assertEquals(1, table.size(), "the unread line still pins the seal");
        log.closeTick();
        assertEquals(0, log.unreadObligations());
        assertEquals(0, table.size(), "the settled obligation released the seal");
    }

    @Test
    void aRangeLineWhoseSeatHasNoSealIsRefusedInsteadOfFallingBackToAnEntry() {
        FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
        RangeSealTable table = new RangeSealTable(8);
        table.publish(seal);
        FrozenDeclarations.Entry entry = new FrozenDeclarations.Entry(seal.seat(), seal.nodeKey(), 1,
            RangeSealFixture.CELL, RangeSealFixture.STATUS, 444L, seal.worldEpoch());
        FrozenDeclarations.Sealed declarations = FrozenDeclarations.of(seal.freeze(),
            seal.planSequence(), List.of(entry));
        assertTrue(declarations.sealed());
        CommitLog log = new CommitLog(() -> 8, order(seal.seat().position()),
            new EffectStore(4096, 16),
            new RangeSource(table, declarations.declarations(), seal.freeze(), seal.worldEpoch(),
                AUTHORIZATION));
        log.beginTick(10L);
        CommitLog.Identity otherCell = identity(seal, RangeSealFixture.CELL2, "features");
        CommitLog.Verdict verdict = log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, AUTHORIZATION,
                11L, 1), otherCell, new EffectStore.Effect(EFFECT, "chunk-materialize", "features",
                List.of("chunk=" + RangeSealFixture.CELL2))));

        assertFalse(verdict.logged());
        assertEquals(RejectCode.VERSION_MISMATCH, verdict.code());
        assertEquals(0L, log.accepted());
        assertEquals(1L, log.typedRefused());
        assertEquals(0, log.unreadObligations());
    }
}
