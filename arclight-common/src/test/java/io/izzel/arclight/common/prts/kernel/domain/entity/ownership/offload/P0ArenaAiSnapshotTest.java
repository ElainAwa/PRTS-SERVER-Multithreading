/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0 arena/version-slot AI snapshot: plan writes a pinned slot, workers read its immutable rows. */
class P0ArenaAiSnapshotTest {

    @Test
    void planPublishesSlotAndWorkersReadItWithoutWrite() throws Exception {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(7L, "world", "region-0", 1, 8);
        assertNotNull(slot);
        ArenaSlot.Lease lease = slot.lease(3L);
        for (int i = 0; i < 8; i++) {
            lease.scratch().write(i, i, 64, 0, 0, 0, 0, 0, 0, 0L);
        }
        assertNotNull(slot.publish(lease));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Double>> reads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int index = i;
                reads.add(workers.submit(() -> slot.lease(3L).scratch().posX(index)));
            }
            for (int i = 0; i < 8; i++) {
                assertEquals((double) i, reads.get(i).get());
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(lease, true));
        assertTrue(ledger.release(lease, true) != ArenaSlot.Release.RELEASED);
    }
}
