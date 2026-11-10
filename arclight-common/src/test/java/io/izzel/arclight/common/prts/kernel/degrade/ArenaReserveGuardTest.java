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

class ArenaReserveGuardTest {

    @Test
    void enoughReserveConsumesInsufficientEntersB5() {
        SharePlanner planner = new SharePlanner();
        planner.plan(List.of("w"), 1L, Map.of());
        DegradeLadder off = new DegradeLadder(() -> true);
        ArenaReserveGuard guard = new ArenaReserveGuard();
        assertFalse(guard.consume(planner, off, ShareTable.ReservePurpose.FORCED_MATERIALIZE,
            Double.MAX_VALUE / 2.0, 1L));
        assertEquals(DegradeLevel.B5, off.sign().deepest());

        SharePlanner enough = new SharePlanner();
        enough.plan(List.of("w"), 1L, Map.of());
        DegradeLadder ladder = new DegradeLadder(() -> true);
        assertTrue(guard.consume(enough, ladder, ShareTable.ReservePurpose.FORCED_MATERIALIZE,
            0.0, 2L));
    }
}
