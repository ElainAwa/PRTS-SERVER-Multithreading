/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every world of the current share table gets a budget.over_world row, zero included, so the
 * acceptance world-count row has the matching number of world groups; a world seen only as an
 * overrun keeps its row, and without a table only overrun worlds are named. */
class BudgetWorldRowsTest {

    private final KernelModule module = KernelModule.instance();

    @Test
    void everyWorldOfTheCurrentTableGetsAZeroRow() {
        SharePlanner shares = module.shares();
        List<String> worlds = List.of("p2a05-zero-a", "p2a05-zero-b", "p2a05-zero-c");
        shares.plan(worlds, 41L, Map.of());

        List<String> lines = KernelReadings.export(module);
        for (String world : worlds) {
            assertTrue(lines.contains("budget.over_world_" + world + "=0"),
                () -> "missing zero row for " + world);
        }
        assertTrue(lines.contains("budget.world_overrun_total=" + shares.worldOverrunTotal()));
    }

    @Test
    void aWorldWithAnOverrunKeepsItsCountAndTheOtherWorldsStayZero() {
        SharePlanner shares = module.shares();
        String overrun = "p2a05-overrun-a";
        String quiet = "p2a05-overrun-b";
        shares.plan(List.of(overrun, quiet), 42L, Map.of());
        shares.noteOverrun(new ShareTable.ShareRow(overrun, ShareClass.ENTITY, 0.0, 1.0),
            "p2a05-site");

        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.contains("budget.over_world_" + overrun + "="
            + shares.worldOverrunCount(overrun)));
        assertTrue(lines.contains("budget.over_world_" + quiet + "="
            + shares.worldOverrunCount(quiet)));
        assertTrue(shares.worldOverrunCount(overrun) > 0L);
    }

    @Test
    void theAcc39PrefixHasOneRowPerWorldAndNoTotalRow() {
        SharePlanner shares = module.shares();
        List<String> worlds = List.of("p2a05-awk-a", "p2a05-awk-b", "p2a05-awk-c", "p2a05-awk-d");
        shares.plan(worlds, 43L, Map.of());
        module.worldSetReadout().observe(worlds, List.of(), List.of());

        List<String> lines = KernelReadings.export(module);
        long prefixRows = lines.stream()
            .filter(line -> line.startsWith("budget.over_world_"))
            .count();
        assertEquals(worlds.size(), prefixRows,
            "the ACC-39 budget.over_world_ prefix counts one row per world");
        assertEquals(module.worldSetReadout().worldCount(), prefixRows);
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("budget.over_world_total=")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("budget.world_overrun_total=")));
    }

    @Test
    void aTableWorldCountThatDiffersFromTheReadoutIsVisibleNotHidden() {
        SharePlanner shares = module.shares();
        List<String> tableWorlds = List.of("p2a05-gap-a", "p2a05-gap-b", "p2a05-gap-c");
        shares.plan(tableWorlds, 44L, Map.of());
        module.worldSetReadout().observe(List.of("p2a05-gap-a", "p2a05-gap-b"), List.of(), List.of());

        List<String> lines = KernelReadings.export(module);
        long prefixRows = lines.stream()
            .filter(line -> line.startsWith("budget.over_world_"))
            .count();
        assertEquals(tableWorlds.size(), prefixRows);
        assertEquals(2L, module.worldSetReadout().worldCount());
        assertTrue(prefixRows != module.worldSetReadout().worldCount(),
            "the test names the mismatch instead of hiding it; live g==w needs one world source");
    }

    @Test
    void withoutATableOnlyOverrunWorldsAreNamed() throws Exception {
        SharePlanner shares = module.shares();
        ShareTable previous = shares.lastTable();
        try {
            setLastTable(shares, null);
            List<String> lines = KernelReadings.export(module);
            long rows = lines.stream()
                .filter(line -> line.startsWith("budget.over_world_"))
                .count();
            assertEquals(shares.overrunWorlds().size(), rows,
                "without a table the rows are exactly the overrun worlds");
            assertFalse(lines.contains("budget.over_world_p2a05-no-table=0"),
                "a world no table and no overrun names gets no row");
        } finally {
            setLastTable(shares, previous);
        }
    }

    private static void setLastTable(SharePlanner shares, ShareTable table) throws Exception {
        Field field = SharePlanner.class.getDeclaredField("lastTable");
        field.setAccessible(true);
        field.set(shares, table);
    }
}
