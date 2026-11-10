/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaReserveGuardPolicyTest {

    @Test
    void defaultOffDoesNotMutateReserveOrLadder() {
        SharePlanner planner = new SharePlanner();
        planner.plan(List.of("w"), 1L, Map.of());
        DegradeLadder off = new DegradeLadder(() -> false);
        ArenaReserveGuard guard = new ArenaReserveGuard();
        assertFalse(guard.consume(planner, off, ShareTable.ReservePurpose.FORCED_MATERIALIZE, 1.0, 1L));
        assertEquals(0L, off.enteredTotal());
        assertEquals(0, off.sign().deepest().ordinal());
    }

    @Test
    void b1ToB5ReturnGateAfterCleanWindow() {
        for (DegradeLevel level : DegradeLevel.values()) {
            if (level == DegradeLevel.NONE) continue;
            DegradeLadder ladder = new DegradeLadder(() -> true);
            ladder.noteEntered(level, 1L);
            for (int i = 0; i < DegradeLadder.DECLARED_RETURN_TICKS; i++) ladder.noteTick(false);
            assertTrue(ladder.noteReturned(level), level.name());
            assertEquals(1L, ladder.counters(level).returned(), level.name());
        }
    }
}
