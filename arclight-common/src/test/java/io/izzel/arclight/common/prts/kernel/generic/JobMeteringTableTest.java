/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobMeteringTableTest {

    @Test
    void meteredChargeFeedsTwoLevelTableAndTriggersDegradeCounters() {
        JobMetering metering = new JobMetering();
        metering.charge("w1", "entity", 10_000.0);
        metering.charge("w2", "example", 0.0);
        SharePlanner planner = new SharePlanner();
        ShareTable table = metering.plan(planner, List.of("w1", "w2"), 5L,
            Map.of("w1", 1.0, "w2", 1.0));
        assertEquals(2 * ShareClass.rowCount(), table.rows().size());
        ShareTable.ShareRow entity = table.row("w1", ShareClass.ENTITY);
        assertNotNull(entity);
        assertTrue(entity.overrun());
        assertNotNull(planner.noteOverrun(entity, "entity.tick"));
        planner.noteHunger("w1");
        assertEquals(1L, planner.hungerEventCount());
        assertEquals(1L, planner.classOverrunCount(ShareClass.ENTITY));
    }

    @Test
    void noChargeMeansNoOverrunAndDuplicateChargeIsNotRepeatedByPlan() {
        JobMetering empty = new JobMetering();
        SharePlanner planner = new SharePlanner();
        ShareTable table = empty.plan(planner, List.of("w1"), 5L, Map.of());
        assertEquals(0.0, table.row("w1", ShareClass.ENTITY).usedMs(), 1e-9);
        assertEquals(0, empty.usedMs().size(), "no charge has no world entry");
        empty.charge("w1", "entity", 1.0);
        empty.charge("w1", "entity", 1.0);
        assertEquals(2.0, empty.usedMs().get("w1").get(ShareClass.ENTITY), 1e-9);
    }
}
