/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I5ShareMeteringTest {

    @Test
    void fiveAssertedClassesAndEightRows() {
        assertEquals(5, ShareClass.ASSERTED.size());
        assertEquals(8, ShareClass.rowCount());
        assertEquals(List.of(ShareClass.ENTITY, ShareClass.BLOCKENTITY, ShareClass.GRAPH,
            ShareClass.EVENT, ShareClass.AI), ShareClass.ASSERTED);
        assertEquals(1.0, ShareClass.ASSERTED.stream().mapToDouble(ShareClass::weight).sum()
            + ShareClass.REGIONTICK.weight() + ShareClass.WORLDRES.weight()
            + ShareClass.OTHER.weight(), 1e-9);
    }

    @Test
    void twoLevelQuotaAndOverrunAreMetered() {
        SharePlanner planner = new SharePlanner();
        EnumMap<ShareClass, Double> used = new EnumMap<>(ShareClass.class);
        used.put(ShareClass.ENTITY, 10_000.0);
        ShareTable table = planner.planFair(List.of("w1", "w2", "w3"), 5L,
            Map.of("w1", used), Map.of("w1", 1.0, "w2", 1.0, "w3", 1.0));
        assertEquals(3 * ShareClass.rowCount(), table.rows().size());
        ShareTable.ShareRow entity = table.row("w1", ShareClass.ENTITY);
        assertNotNull(entity);
        assertTrue(entity.overrun());
        SharePlanner.WouldDegrade degrade = planner.noteOverrun(entity, "entity.tick");
        assertNotNull(degrade);
        assertEquals("w1", degrade.worldId());
        planner.noteHunger("w1");
        assertEquals(1L, planner.hungerEventCount());
        assertEquals(1L, planner.worldOverrunCount("w1"));
        assertEquals(1L, planner.classOverrunCount(ShareClass.ENTITY));
    }
}
