/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S1 full component pipeline: self+neighbour arena rows -> WorkPlan -> pure WorkBody -> commit once. */
class AiSensorFullPipelineTest {

    record Self(long id, double x, double y, double z) {
    }

    record Neighbour(long id, double x, double y, double z, boolean visible) {
    }

    private static List<Long> nearest(ArenaSlot.Lease lease, int selfIndex, int selfCount,
                                      int neighbourStart, int neighbourCount) {
        double sx = lease.scratch().posX(selfIndex);
        double sy = lease.scratch().posY(selfIndex);
        double sz = lease.scratch().posZ(selfIndex);
        Map<Long, Double> distances = new HashMap<>();
        for (int i = neighbourStart; i < neighbourStart + neighbourCount; i++) {
            if (lease.scratch().flags(i) != 1L) continue;
            double dx = lease.scratch().posX(i) - sx;
            double dy = lease.scratch().posY(i) - sy;
            double dz = lease.scratch().posZ(i) - sz;
            if (dx * dx + dy * dy + dz * dz <= 256.0) {
                distances.put(lease.scratch().flags(i) == 1L ? (long) (i - neighbourStart + 100) : -1L,
                    dx * dx + dy * dy + dz * dz);
            }
        }
        List<Long> ids = new ArrayList<>(distances.keySet());
        ids.sort(Comparator.comparingDouble(distances::get));
        return ids;
    }

    @Test
    void fullPipelineFromCaptureToCommit() throws Exception {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(21L, "world", "region-0", 1, 8 + 16);
        ArenaSlot.Lease lease = slot.lease(4L);
        for (int i = 0; i < 8; i++) {
            lease.scratch().write(i, i, 64, 0, 0, 0, 0, 0, 0, 0L);
        }
        for (int i = 0; i < 16; i++) {
            // encode neighbour row at 8+i; visible flag 1 for all (host LOS captured elsewhere)
            lease.scratch().write(8 + i, i, 64, 0, 0, 0, 0, 0, 0, 1L);
        }
        assertTrue(slot.publish(lease));
        EntityCandidateView.Builder builder = EntityCandidateView.builder("world", 4L);
        for (int i = 0; i < 8; i++) {
            builder.add(i, i / 4, 0, lease.scratch().posX(i), lease.scratch().posY(i),
                lease.scratch().posZ(i), 0, 0, 0, 0, 0, 0L);
        }
        WorkPlan plan = WorkPlan.freeze(1L, 4L, List.of(builder.build()), 16, 1L);
        assertEquals(1, plan.taskCount());
        assertEquals(8, plan.tasks().get(0).entityCount());

        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<Long>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int self = i;
                results.add(workers.submit(() -> nearest(lease, self, 8, 8, 16)));
            }
            for (int i = 0; i < 8; i++) {
                List<Long> ids = results.get(i).get();
                assertTrue(ids.contains(100L + i));
            }
        } finally {
            workers.shutdownNow();
        }
        int committed = 0;
        for (int i = 0; i < 8; i++) {
            List<Long> ids = nearest(lease, i, 8, 8, 16);
            if (!ids.isEmpty()) committed++;
        }
        assertEquals(8, committed);
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(lease, true));
    }
}
