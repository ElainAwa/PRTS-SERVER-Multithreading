/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.degrade.ArenaReserveGuard;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class I7DualOverrunTest {

    @Test
    void overrunCountsWorldAndClassTogetherAndReserveGuardDoesNotDoubleCount() {
        SharePlanner planner = new SharePlanner();
        EnumMap<ShareClass, Double> used = new EnumMap<>(ShareClass.class);
        used.put(ShareClass.ENTITY, 10_000.0);
        ShareTable table = planner.plan(List.of("w1"), 1L, Map.of("w1", used));
        ShareTable.ShareRow row = table.row("w1", ShareClass.ENTITY);
        assertNotNull(row);
        assertNotNull(planner.noteOverrun(row, "entity.tick"));
        assertEquals(1L, planner.dualOverrunCount("w1", ShareClass.ENTITY));
        assertEquals(1L, planner.dualOverrunTotal());
        assertEquals(1L, planner.worldOverrunCount("w1"));
        assertEquals(1L, planner.classOverrunCount(ShareClass.ENTITY));

        DegradeLadder on = new DegradeLadder(() -> true);
        new ArenaReserveGuard().consume(planner, on, ShareTable.ReservePurpose.FORCED_MATERIALIZE,
            Double.MAX_VALUE / 2.0, 2L);
        assertEquals(DegradeLevel.B5, on.sign().deepest());
        assertEquals(1L, planner.dualOverrunCount("w1", ShareClass.ENTITY));
    }
}
