/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The row sets and the frozen generations of one segment: the ownership set and the observed rows
 * are booked apart, a row of one is never a row of the other, and the host ordinal of a row is the
 * order the host visits it by. */
class SegmentWorkTest {

    private static final long TICK = 900L;
    private static final String WORLD = "minecraft:overworld";
    private static final long WORLD_EPOCH = 11L;
    private static final long SEGMENT_EPOCH = 5L;
    private static final long UUID_HIGH = 0x0A0B0C0DL;
    private static final long UUID_LOW = 0x01020304L;

    @Test
    void theTwoRowSetsShareNoRowAndCoverEveryOrdinal() {
        SegmentWork segment = segment();
        segment.claim(1, 101L, UUID_HIGH, UUID_LOW);
        segment.book(0, 7L);
        segment.observe(2, 102L);
        segment.claim(3, 103L, UUID_HIGH, UUID_LOW);
        segment.book(2, 8L);
        segment.observe(4, 104L);
        assertEquals(2, segment.ownedRows());
        assertEquals(2, segment.observedRows());
        assertEquals(1, segment.owned(0).entityId());
        assertEquals(2, segment.observed(0).entityId());
        // One ordinal per row of the segment, owned and observed interleaved in the plan order.
        assertEquals(2, segment.owned(1).hostOrdinal());
        assertEquals(3, segment.observed(1).hostOrdinal());
        assertEquals(3, segment.entityIdOf(2), "the ordinal no longer names the row it was frozen for");
        SegmentWork.Frame frame = segment.closeFrame(null);
        assertEquals(0, frame.setConflicts(), "two sets that share no row were reported as sharing one");
        assertTrue(frame.ledgerOk());
        assertEquals(2, frame.ownedRows());
        assertEquals(2, frame.observedRows());
    }

    @Test
    void anObservedRowIsNeverAnOwnershipRow() {
        SegmentWork segment = segment();
        segment.observe(9, 109L);
        assertFalse(segment.ownsOrdinal(0), "an observed ordinal was marked as owned");
        assertEquals(0, segment.observedOrdinal(9));
        assertEquals(-1, segment.observedOrdinal(10), "a row nobody booked was reported as observed");
    }

    @Test
    void aRowBookedInBothSetsIsFoundByTheFrame() {
        SegmentWork segment = segment();
        segment.claim(5, 105L, UUID_HIGH, UUID_LOW);
        segment.book(0, 1L);
        segment.observe(6, 106L);
        segment.conflictForFault(5, 105L, 0);
        SegmentWork.Frame frame = segment.closeFrame(null);
        assertEquals(1, frame.setConflicts());
        assertEquals(5, segment.conflictingEntity());
        assertEquals(0, segment.conflictingOrdinal());
    }

    @Test
    void aRowTheHostPassedIsRefusedAndTheOrderKeepsMovingForward() {
        SegmentWork segment = segment();
        assertTrue(segment.acceptOrdinal(4));
        assertTrue(segment.acceptOrdinal(5));
        assertFalse(segment.acceptOrdinal(5), "the same ordinal was accepted twice");
        assertFalse(segment.acceptOrdinal(3), "an ordinal behind the host was accepted");
        assertTrue(segment.acceptOrdinal(6));
        assertEquals(2, segment.ordinalBroken());
    }

    @Test
    void theThreeGenerationsAreFrozenWithTheRow() {
        SegmentWork segment = segment();
        int ordinal = segment.claim(21, 121L, UUID_HIGH, UUID_LOW);
        segment.book(ordinal, 3L);
        segment.readSet().freezeNeighbourVerdict(ordinal, true);
        assertEquals(21, segment.entityIdOf(ordinal));
        assertEquals(UUID_HIGH, segment.uuidHighOf(ordinal));
        assertEquals(UUID_LOW, segment.uuidLowOf(ordinal));
        assertEquals(SEGMENT_EPOCH, segment.segmentEpoch());
        assertEquals(WORLD, segment.readSet().worldId());
        assertEquals(WORLD_EPOCH, segment.readSet().worldEpoch());
        assertTrue(segment.neighbourClearOf(ordinal),
            "the frozen neighbour verdict of the row was lost");
        assertEquals(1, segment.readSet().frozenRows());
        assertEquals(21, segment.owned(0).entityId());
        assertEquals(121L, segment.owned(0).entityEpoch());
        assertEquals(3L, segment.owned(0).token());
        assertEquals(-1, segment.entityIdOf(ordinal + 1), "a row nobody froze was answered");
    }

    @Test
    void aCommitOfARowTheSegmentDoesNotOwnIsCounted() {
        SegmentWork segment = segment();
        segment.claim(31, 131L, UUID_HIGH, UUID_LOW);
        segment.book(0, 1L);
        segment.noteCommit(0);
        segment.noteCommit(7);
        SegmentWork.Frame frame = segment.closeFrame(null);
        assertEquals(1, frame.unbookedCommits(),
            "a commit of a row the segment does not own was not counted");
    }

    @Test
    void theTableKeepsOneSegmentPerWorldAndFreezesItsGeneration() {
        SegmentWork.Table table = new SegmentWork.Table();
        SegmentWork first = table.of(null, WORLD, WORLD_EPOCH, TICK);
        SegmentWork again = table.of(null, WORLD, WORLD_EPOCH + 1L, TICK);
        assertEquals(1, table.rows(), "a second segment was frozen for the same world");
        assertSame(first, again);
        assertEquals(WORLD_EPOCH, again.readSet().worldEpoch(),
            "the frozen generation of the world moved inside one tick");
        assertEquals(TICK, again.tickIndex());
        table.reset();
        assertEquals(0, table.rows());
        assertNull(table.live(null), "a reset table still answers with a segment");
    }

    private static SegmentWork segment() {
        return new SegmentWork(TICK, WORLD, WORLD_EPOCH, null, SEGMENT_EPOCH);
    }
}
