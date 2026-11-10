/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSensorCaptureTest {

    @Test
    void hostRowsWriteIntoPlanArenaAndWorkersReadOnly() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(11L, "world", "region-0", 1, 8);
        ArenaSlot.Lease lease = slot.lease(2L);
        List<AiSensorCapture.Row> rows = List.of(
            new AiSensorCapture.Row(1, 0, 64, 0, 16, true),
            new AiSensorCapture.Row(2, 1, 64, 0, 16, false));
        AiSensorCapture.write(lease, rows);
        assertTrue(slot.publish(lease));
        assertEquals(2, slot.lease(2L).scratch().filled());
        assertEquals(0.0, slot.lease(2L).scratch().posX(0));
        assertEquals(1.0, slot.lease(2L).scratch().posX(1));
    }
}
