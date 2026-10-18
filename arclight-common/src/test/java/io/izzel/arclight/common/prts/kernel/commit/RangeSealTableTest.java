/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The immutable publish table: publish outcomes, exact lookup, pin and release, reset and the
 * generation a window captured. Every assertion drives the real table and the real seal record. */
class RangeSealTableTest {

    private static RangeSealTable table() {
        return new RangeSealTable(8);
    }

    private static FrozenDeclarations.RangeSeal seal(long cell, String status) {
        RangeSealFixture fixture = new RangeSealFixture();
        fixture.cells(cell);
        fixture.cell(cell);
        fixture.status(status);
        return fixture.build();
    }

    @Test
    void theSameSealPublishedTwiceIsIdempotentAndKeepsOneEntry() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");

        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        assertEquals(RangeSealTable.Publish.IDEMPOTENT, table.publish(seal(RangeSealFixture.CELL,
            "full")));
        assertEquals(1, table.size());
        assertEquals(1L, table.published());
        assertEquals(1L, table.idempotent());
        assertSame(table.lookup(seal.freeze(), seal.seat(), seal.statuses().keySet().iterator()
            .next()), table.lookup(seal.freeze(), seal.seat(), seal.statuses().keySet().iterator()
            .next()));
    }

    @Test
    void anotherValueUnderOneKeyIsAConflictAndTheFirstStays() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal first = seal(RangeSealFixture.CELL, "full");
        RangeSealFixture other = new RangeSealFixture();
        other.cells(RangeSealFixture.CELL).cell(RangeSealFixture.CELL).status("features")
            .instance(RangeSealFixture.INSTANCE + 1L);
        FrozenDeclarations.RangeSeal second = other.build();

        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(first));
        assertEquals(RangeSealTable.Publish.CONFLICT, table.publish(second));
        assertEquals(1, table.size());
        assertEquals(1L, table.conflicts());
        assertEquals(first, table.lookup(first.freeze(), first.seat(),
            first.statuses().keySet().iterator().next()), "the first value is not replaced");
    }

    @Test
    void aFullTableRefusesTheNewcomerAndKeepsEveryEntry() {
        RangeSealTable table = new RangeSealTable(1);
        FrozenDeclarations.RangeSeal first = seal(RangeSealFixture.CELL, "full");
        FrozenDeclarations.RangeSeal second = seal(RangeSealFixture.CELL2, "full");

        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(first));
        IdentityWitness.Obligation held = table.oblige(first);
        assertNotNull(held);
        assertEquals(RangeSealTable.Publish.FULL, table.publish(second));
        assertEquals(1, table.size());
        assertEquals(1L, table.fulls());
        assertEquals(first, table.lookup(first.freeze(), first.seat(),
            first.statuses().keySet().iterator().next()), "a full table does not evict a pinned seal");
        table.release(first);
        assertEquals(first, table.lookup(first.freeze(), first.seat(),
            first.statuses().keySet().iterator().next()), "the obligation still pins the seal");
        held.settle();
        assertNull(table.lookup(first.freeze(), first.seat(),
            first.statuses().keySet().iterator().next()), "release then settle removes it");
    }

    @Test
    void aLookupIsTheExactKeyAndNeverTheNewestOne() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));

        FrozenDeclarations.FreezeKey other = new FrozenDeclarations.FreezeKey(
            RangeSealFixture.LIFECYCLE, 2L);
        assertNotNull(table.lookup(seal.freeze(), seal.seat(), RangeSealFixture.CELL));
        assertNull(table.lookup(other, seal.seat(), RangeSealFixture.CELL),
            "another generation is not answered with this seal");
        assertNull(table.lookup(seal.freeze(), seal.seat(), RangeSealFixture.CELL2));
        assertTrue(table.hasSeat(seal.freeze(), seal.seat()));
        assertFalse(table.hasSeat(other, seal.seat()));
        assertTrue(table.hasSeat(seal.seat()), "the seat carries a seal under some freeze");
    }

    @Test
    void aPinnedSealSurvivesTheWindowCloseAndLeavesWhenItsObligationSettles() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        IdentityWitness.Obligation obligation = table.oblige(seal);

        assertTrue(table.release(seal));
        assertEquals(1, table.size(), "a pinned seal is not removed by the window release");
        obligation.settle();
        assertEquals(0, table.size());
        assertEquals(0, table.pinnedCount());
        obligation.settle();
        assertEquals(0, table.size(), "a repeated settlement does not settle another obligation");

        table.publish(seal);
        IdentityWitness.Obligation first = table.oblige(seal);
        IdentityWitness.Obligation second = table.oblige(seal);
        assertTrue(table.release(seal));
        second.settle();
        assertEquals(1, table.size(), "one of two obligations is still owed");
        first.settle();
        assertEquals(0, table.size());
    }

    @Test
    void aResetEmptiesTheTableAndTheOldSnapshotStaysReadable() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        long generation = table.generation();
        RangeSealTable.Snapshot snapshot = table.snapshot();

        table.reset();

        assertEquals(generation + 1L, table.generation());
        assertEquals(0, table.size());
        assertNull(table.lookup(seal.freeze(), seal.seat(), RangeSealFixture.CELL));
        assertEquals(1, snapshot.seals().size(), "a snapshot is a value");
        assertNotNull(snapshot.sealAt(seal.planSequence(), seal.seat().position(),
            seal.statuses().keySet().iterator().next()));
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal),
            "a publish of the next generation is admitted");
    }

    @Test
    void aPublishOfTheGenerationAWindowCapturedBeforeAResetIsRefused() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");
        long captured = table.generation();
        table.reset();

        assertEquals(RangeSealTable.Publish.REJECTED, table.publish(seal, captured));
        assertEquals(0, table.size());
        assertEquals(1L, table.rejected());
        assertEquals(captured + 1L, table.generation());
    }

    @Test
    void aSealNamingTwoStatusesOrNoCellOrSeventeenCellsIsRefused() {
        RangeSealTable table = table();
        RangeSealFixture fixture = new RangeSealFixture();
        FrozenDeclarations.RangeSeal canonical = fixture.build();
        FrozenDeclarations.RangeSeal twoStatuses = new FrozenDeclarations.RangeSeal(canonical.seat(),
            canonical.nodeKey(), canonical.role(), canonical.worldId(), canonical.mailbox(),
            canonical.freezeOrdinal(), canonical.planSequence(), canonical.freeze(), canonical.cells(),
            canonical.spaceDigest(), java.util.Map.of(RangeSealFixture.CELL,
                new FrozenDeclarations.CellStatus("full", canonical.stamp()),
                RangeSealFixture.CELL2, new FrozenDeclarations.CellStatus("features",
                    canonical.stamp())), canonical.statusMapDigest(), canonical.instanceId(),
            canonical.worldEpoch(), canonical.stamp());
        long[] seventeen = new long[17];
        for (int index = 0; index < seventeen.length; index++) {
            seventeen[index] = index + 1L;
        }
        RangeSealFixture wide = new RangeSealFixture();
        wide.cells(seventeen).cell(1L);
        FrozenDeclarations.RangeSeal tooMany = wide.build();
        RangeSealFixture empty = new RangeSealFixture();
        empty.cells(new long[0]).cell(0L);

        assertEquals(RangeSealTable.Publish.REJECTED, table.publish(twoStatuses));
        assertEquals(RangeSealTable.Publish.REJECTED, table.publish(tooMany));
        assertEquals(RangeSealTable.Publish.REJECTED,
            table.publish(empty.build()));
        assertEquals(0, table.size());
        assertEquals(3L, table.rejected());
        assertFalse(twoStatuses.wellFormed());
        assertFalse(tooMany.wellFormed());
    }

    @Test
    void aSnapshotFoldsEveryPublicFieldAndIgnoresTheInputOrder() {
        FrozenDeclarations.RangeSeal first = seal(RangeSealFixture.CELL, "full");
        FrozenDeclarations.RangeSeal second = seal(RangeSealFixture.CELL2, "features");
        RangeSealTable one = table();
        one.publish(first);
        one.publish(second);
        RangeSealTable other = table();
        other.publish(second);
        other.publish(first);

        RangeSealTable.Snapshot snapshot = one.snapshot();
        assertEquals(other.snapshot().digest(), snapshot.digest(),
            "the same seal set folds the same whatever order it was published in");
        assertEquals(snapshot.digest(), snapshot.recompute());
        assertTrue(snapshot.wellFormed());
        assertTrue(snapshot.has(first.planSequence(), first.seat().position(),
            RangeSealFixture.CELL));
        assertFalse(snapshot.has(first.planSequence(), first.seat().position(),
            RangeSealFixture.CELL2 + 10L));

        RangeSealFixture changed = new RangeSealFixture();
        changed.cells(RangeSealFixture.CELL).cell(RangeSealFixture.CELL).status("empty");
        RangeSealTable edited = table();
        edited.publish(changed.build());
        assertFalse(snapshot.digest() == edited.snapshot().digest(),
            "a changed public field changes the fold");
    }

    @Test
    void aSeatOfAnotherLifecycleIsNotAnsweredByTheCurrentOne() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal old = seal(RangeSealFixture.CELL, "full");
        table.publish(old);
        FrozenDeclarations.FreezeKey next = new FrozenDeclarations.FreezeKey(
            RangeSealFixture.LIFECYCLE, 2L);
        RangeSealFixture newer = new RangeSealFixture();
        newer.freeze(next).cells(RangeSealFixture.CELL).cell(RangeSealFixture.CELL)
            .instance(RangeSealFixture.INSTANCE + 7L);
        table.publish(newer.build());

        assertEquals(2, table.size());
        RangeSealTable.Snapshot snapshot = table.snapshot();
        assertFalse(snapshot.wellFormed(), "two freezes in one snapshot are not one closure");
        assertNull(table.locate(old.seat(), RangeSealFixture.CELL),
            "two candidates for one seat are answered as ambiguous");
    }

    @Test
    void anObligationOfASealTheTableDoesNotHoldSettlesNothing() {
        RangeSealTable table = table();
        FrozenDeclarations.RangeSeal seal = seal(RangeSealFixture.CELL, "full");
        IdentityWitness.Obligation obligation = table.oblige(seal);
        assertSame(IdentityWitness.Obligation.NONE, obligation);
        List<FrozenDeclarations.RangeSeal> seals = new ArrayList<>();
        seals.add(seal);
        RangeSealTable.Snapshot snapshot = RangeSealTable.Snapshot.of(seal.freeze(),
            seal.planSequence(), seals);
        assertEquals(snapshot.digest(), snapshot.recompute());
    }
}
