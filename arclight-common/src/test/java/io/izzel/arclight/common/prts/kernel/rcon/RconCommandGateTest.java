/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The B4 gate is sourced from the real EVENT share row and the ladder rung, never from queue
 * pressure or another class's overrun. */
class RconCommandGateTest {

    private static ShareTable table(ShareClass shareClass, double shareMs, double usedMs) {
        return new ShareTable(1L,
            List.of(new ShareTable.ShareRow("minecraft:overworld", shareClass, shareMs, usedMs)),
            new ShareTable.ReserveRow(0.0, 0.0, Map.of()), 0.0, 50.0);
    }

    @Test
    void onlyTheEventRowOverItsMarginLetsTheCommandWait() {
        assertTrue(RconCommandGate.shouldDefer(table(ShareClass.EVENT, 1.0, 2.0),
            DegradeLevel.B4, true, true));
        assertFalse(RconCommandGate.shouldDefer(table(ShareClass.EVENT, 2.0, 1.0),
            DegradeLevel.B4, true, true));
        assertFalse(RconCommandGate.shouldDefer(table(ShareClass.AI, 1.0, 2.0),
            DegradeLevel.B4, true, true));
    }

    @Test
    void theLadderSwitchesCannotReplaceTheEventSource() {
        assertFalse(RconCommandGate.shouldDefer(table(ShareClass.EVENT, 1.0, 2.0),
            DegradeLevel.B3, true, true));
        assertFalse(RconCommandGate.shouldDefer(table(ShareClass.EVENT, 1.0, 2.0),
            DegradeLevel.B4, false, true));
        assertFalse(RconCommandGate.shouldDefer(table(ShareClass.EVENT, 1.0, 2.0),
            DegradeLevel.B4, true, false));
        assertFalse(RconCommandGate.shouldDefer(null, DegradeLevel.B4, true, true));
    }
}
