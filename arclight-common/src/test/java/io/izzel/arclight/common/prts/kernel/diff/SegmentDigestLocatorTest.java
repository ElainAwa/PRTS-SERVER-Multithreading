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
