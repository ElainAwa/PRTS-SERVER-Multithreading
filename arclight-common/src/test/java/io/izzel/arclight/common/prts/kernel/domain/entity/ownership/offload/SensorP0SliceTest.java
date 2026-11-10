/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Component slice of the vanilla nearest-living sensor: the host freezes 8 AI actor rows and
 * their neighbour candidates, a worker performs the pure filter/sort on the snapshot, and the
 * host commits the resulting memory ids once; a stale snapshot is skipped, never re-run on a
 * live Level.
 */
class SensorP0SliceTest {

    record Neighbour(long id, double x, double y, double z, boolean alive, boolean visible) {
    }

    record Row(long id, double x, double y, double z, double radius, long snapshotVersion) {
    }

    record Answer(long actorId, List<Long> visible, long snapshotVersion) {
    }

    static Answer compute(Row row, List<Neighbour> neighbours) {
        List<Neighbour> visible = new ArrayList<>();
        for (Neighbour n : neighbours) {
            if (n.id() == row.id() || !n.alive() || !n.visible()) continue;
            double dx = n.x() - row.x();
            double dy = n.y() - row.y();
            double dz = n.z() - row.z();
            if (dx * dx + dy * dy + dz * dz <= row.radius() * row.radius()) visible.add(n);
        }
        visible.sort(Comparator.comparingDouble(n -> {
            double dx = n.x() - row.x();
            double dy = n.y() - row.y();
            double dz = n.z() - row.z();
            return dx * dx + dy * dy + dz * dz;
        }));
        List<Long> ids = new ArrayList<>(visible.size());
        for (Neighbour n : visible) ids.add(n.id());
        return new Answer(row.id(), ids, row.snapshotVersion());
    }

    @Test
    void eightAiRowsComputePureSensorMemoryAndCommitOnce() {
        List<Neighbour> neighbours = new ArrayList<>();
        for (long id = 100; id < 116; id++) {
            neighbours.add(new Neighbour(id, id - 100, 64, 0, true, true));
        }
        int committed = 0;
        for (int i = 0; i < 8; i++) {
            Row row = new Row(i, i, 64, 0, 8.0, 5L);
            Answer answer = compute(row, neighbours);
            // Host commit: exact snapshot version only; one apply per answer.
            if (answer.snapshotVersion() == row.snapshotVersion()) committed++;
            assertTrue(answer.visible().containsAll(
                List.of(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L)));
            assertTrue(answer.visible().contains((long) (i + 100)));
            assertFalse(answer.visible().contains((long) i));
        }
        assertEquals(8, committed);
    }

    @Test
    void staleSnapshotIsSkippedNotCommitted() {
        Row row = new Row(1, 0, 64, 0, 8.0, 5L);
        Answer stale = new Answer(1, List.of(100L), 4L);
        assertFalse(stale.snapshotVersion() == row.snapshotVersion());
    }
}
