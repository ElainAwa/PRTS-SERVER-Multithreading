/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world-share budget: eight worlds divide the available budget instead of multiplying the
 * per-world cap, while one world keeps the declared cap and weighted shares remain proportional
 * when the cap does not bind. */
class P2F03ShareConservationTest {

    private final SharePlanner planner = new SharePlanner();

    @Test
    void eightWorldsSumToTheAvailableBudgetAndStayConserved() {
        List<String> worlds = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            worlds.add("p2f03-world-" + index);
        }
        ShareTable table = planner.plan(worlds, 1L, Map.of());
        double available = KernelSettings.eBudgetMs() - KernelSettings.reserveMs()
            - KernelSettings.hostOverheadMs();

        assertEquals(available, table.sumSharesMs(), 1.0e-9);
        assertEquals(available / 8.0, sharesOf(table, worlds.get(0)), 1.0e-9);
        assertEquals(available / 8.0, sharesOf(table, worlds.get(7)), 1.0e-9);
        ConservationCheck check = planner.checkConservation(table);
        assertTrue(check.ok());
        assertEquals(table.sumSharesMs() + table.reserve().reserveMs()
            + table.hostOverheadMs(), check.plannedMs(), 1.0e-9);
        assertTrue(check.plannedMs() <= table.eBudgetMs());
    }

    @Test
    void oneWorldKeepsTheDeclaredCap() {
        ShareTable table = planner.plan(List.of("p2f03-single"), 1L, Map.of());

        assertEquals(KernelSettings.worldShareMs(), table.sumSharesMs(), 1.0e-9);
        assertTrue(planner.checkConservation(table).ok());
    }

    @Test
    void weightedPlanFairKeepsProportionsWhenTheCapDoesNotBind() {
        List<String> worlds = new ArrayList<>();
        Map<String, Double> weights = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 12; index++) {
            String world = "p2f03-weighted-" + index;
            worlds.add(world);
            weights.put(world, index < 2 ? 2.0 : 1.0);
        }
        ShareTable table = planner.planFair(worlds, 1L, Map.of(), weights);
        double available = KernelSettings.eBudgetMs() - KernelSettings.reserveMs()
            - KernelSettings.hostOverheadMs();
        double weighted = sharesOf(table, worlds.get(0));
        double equal = sharesOf(table, worlds.get(2));

        assertEquals(2.0, weighted / equal, 1.0e-9);
        assertEquals(available, table.sumSharesMs(), 1.0e-9);
        assertTrue(weighted < KernelSettings.worldShareMs());
        assertTrue(planner.checkConservation(table).ok());
    }

    @Test
    void zeroWorldsKeepOnlyTheReserveAndHostAndDoNotOverflow() {
        ShareTable table = planner.plan(List.of(), 1L, Map.of());

        assertEquals(0.0, table.sumSharesMs(), 1.0e-9);
        assertEquals(KernelSettings.reserveMs(), table.reserve().reserveMs(), 1.0e-9);
        assertEquals(KernelSettings.hostOverheadMs(), table.hostOverheadMs(), 1.0e-9);
        assertTrue(planner.checkConservation(table).ok());
    }

    private static double sharesOf(ShareTable table, String world) {
        double sum = 0.0;
        for (ShareTable.ShareRow row : table.rowsOf(world)) {
            sum += row.shareMs();
        }
        return sum;
    }
}
