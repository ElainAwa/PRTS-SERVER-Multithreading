/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a segment digest can say after one row of a segment changed: the per-row entries of the
 * committed frame name the row, and the entries of every other segment stay equal. A digest that
 * keeps no per-row value can say that a segment changed but not which row did.
 */
class SegmentDigestLocatorTest {

    private static final String DOMAIN = "entity";
    private static final long TICK = 1200L;
    private static final int ROWS = 64;
    private static final int INJECTED = 37;

    @Test
    void oneChangedRowIsNamedByTheEntityDigest() {
        List<StateHasher.Slice> committed = rows(1, ROWS);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        changed.set(INJECTED, withVelocity(committed.get(INJECTED), 0.5));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, HashWhitelist.bitexact());
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, HashWhitelist.bitexact());
        assertNotEquals(before.value(), after.value(),
            "one changed row must change the segment digest");
        int divergent = 0;
        String key = null;
        for (Map.Entry<String, Long> entry : before.entityDigests().entrySet()) {
            Long other = after.entityDigests().get(entry.getKey());
            if (other == null || other.longValue() != entry.getValue().longValue()) {
                divergent++;
                key = entry.getKey();
            }
        }
        assertEquals(1, divergent, "one changed row must leave one divergent entity entry");
        StateHasher.Slice row = committed.get(INJECTED);
        assertEquals(sliceKey(row), key, "the divergent key must name world, region, batch and row");
    }

    @Test
    void oneChangedRowLeavesTheOtherSegmentEqual() {
        List<StateHasher.Slice> firstSegment = rows(1, ROWS);
        List<StateHasher.Slice> secondSegment = rows(2, ROWS);
        List<StateHasher.Slice> committed = new ArrayList<>(firstSegment);
        committed.addAll(secondSegment);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        changed.set(ROWS + INJECTED, withVelocity(committed.get(ROWS + INJECTED), 0.5));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, HashWhitelist.bitexact());
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, HashWhitelist.bitexact());
        String moved = sliceKey(committed.get(ROWS + INJECTED));
        int divergent = 0;
        for (Map.Entry<String, Long> entry : before.entityDigests().entrySet()) {
            Long other = after.entityDigests().get(entry.getKey());
            if (other == null || other.longValue() != entry.getValue().longValue()) {
                divergent++;
                assertEquals(moved, entry.getKey(), "only the changed row may diverge");
            }
        }
        assertEquals(1, divergent, "the segment that did not change must stay equal");
        assertTrue(before.entityDigests().size() == 2 * ROWS,
            "a committed frame keeps one entry per row");
    }

    @Test
    void theRowIndexNamesTheRowWithoutAMap() {
        List<StateHasher.Slice> committed = rows(1, ROWS);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        changed.set(INJECTED, withVelocity(committed.get(INJECTED), 0.5));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, HashWhitelist.bitexact());
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, HashWhitelist.bitexact());
        DiffProbe probe = new DiffProbe();
        probe.compare(after, before);
        DiffProbe.DiffReport report = probe.report();
        assertEquals(0L, report.unattributed());
        assertEquals(1L, report.locatedRows());
        assertEquals(committed.get(INJECTED).entitySeq(), report.firstForkEntityId());
        assertEquals(INJECTED, report.firstForkHostOrdinal(),
            "the offset of the row in the frame is what the descent answers with");
        assertEquals("velocity", report.firstForkField());
        assertTrue(report.forkLine().contains("host_ordinal=" + INJECTED));
        assertTrue(report.forkLine().contains("entity_id=" + committed.get(INJECTED).entitySeq()));
    }

    @Test
    void theHeaderDoesNotMoveWhenARowValueMoves() {
        List<StateHasher.Slice> committed = rows(1, ROWS);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        changed.set(INJECTED, withVelocity(committed.get(INJECTED), 0.5));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, HashWhitelist.bitexact());
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, HashWhitelist.bitexact());
        assertNotEquals(before.value(), after.value());
        assertEquals(before.segmentHeaderDigest(), after.segmentHeaderDigest(),
            "the header names the frame, not the row values, so it cannot stand in for the descent");
        assertEquals(StateHasher.ROW_INDEX_ID, before.rows().layoutId());
        assertEquals(ROWS, before.rows().size());
        for (int index = 0; index < ROWS; index++) {
            assertEquals(committed.get(index).entitySeq(), before.rows().entitySeq(index));
        }
    }

    @Test
    void theRowIndexOfASecondSegmentIsItsOffsetInTheFrame() {
        List<StateHasher.Slice> firstSegment = rows(1, ROWS);
        List<StateHasher.Slice> secondSegment = rows(2, ROWS);
        List<StateHasher.Slice> committed = new ArrayList<>(firstSegment);
        committed.addAll(secondSegment);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        changed.set(ROWS + INJECTED, withVelocity(committed.get(ROWS + INJECTED), 0.5));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, HashWhitelist.bitexact());
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, HashWhitelist.bitexact());
        DiffProbe probe = new DiffProbe();
        probe.compare(after, before);
        DiffProbe.DiffReport report = probe.report();
        // The frame folds its rows by world, region and entity sequence, so a row of a later
        // segment is named by its offset in that order - not by the offset it had in the plan.
        List<StateHasher.Slice> frame = new ArrayList<>(changed);
        frame.sort(java.util.Comparator.comparing(StateHasher.Slice::worldId)
            .thenComparing(StateHasher.Slice::regionId)
            .thenComparingLong(StateHasher.Slice::entitySeq));
        int ordinal = frame.indexOf(changed.get(ROWS + INJECTED));
        assertEquals(0L, report.unattributed());
        assertEquals(committed.get(ROWS + INJECTED).entitySeq(), report.firstForkEntityId());
        assertEquals(ordinal, report.firstForkHostOrdinal());
        assertEquals(2L, report.firstForkBatch(), "the row carries the batch it belongs to");
    }

    private static String sliceKey(StateHasher.Slice slice) {
        return slice.worldId() + "|" + slice.regionId() + "|" + slice.batchId() + "|"
            + slice.entitySeq();
    }

    private static StateHasher.Slice withVelocity(StateHasher.Slice slice, double velocity) {
        return new StateHasher.Slice(slice.worldId(), slice.regionId(), slice.batchId(),
            slice.entitySeq(), slice.x(), slice.y(), slice.z(), slice.yaw(), slice.pitch(),
            velocity, slice.velY(), slice.velZ(), slice.flags(), slice.slotGeneration(),
            slice.segmentRef());
    }

    private static List<StateHasher.Slice> rows(int segment, int count) {
        List<StateHasher.Slice> list = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            list.add(new StateHasher.Slice("world", "r0.0", segment, 100000L + index,
                528.5 + index, 64.0, 528.5, index % 360, 0.0, 0.1, -0.08, 0.001 * index,
                (index & 1) == 0 ? 1L : 0L, 0L, segment));
        }
        return list;
    }
}
