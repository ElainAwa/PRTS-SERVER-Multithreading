/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lifecycle of the internal settlement ledger: unload, reload, reset, the tick retention bound
 * and the world bound. Every case keeps the parent amount counted once and keeps the ledger
 * bounded; a late report of a retired world is refused instead of being counted again. */
class WorldResChildLedgerLifecycleTest {

    private static final long OPENING = 3L;
    private static final WorldResChildBudget.ChildDemand DEMAND =
        WorldResChildBudget.ChildDemand.ofTaskNanos(2_000_000L, 1_000_000L);

    private static ShareTable table(long tick) {
        return new SharePlanner().plan(List.of("world-a", "world-b"), tick, Map.of());
    }

    private static ShareTable tableOf(List<String> worlds, long tick) {
        return new SharePlanner().plan(worlds, tick, Map.of());
    }

    private static double parent() {
        return table(20L).row("world-a", ShareClass.WORLDRES).shareMs();
    }

    @Test
    void anUnloadedWorldDropsItsSettlementsAndALateReportIsStale() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ShareTable table = table(20L);
        ledger.settle(table, "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.settle(table, "world-b", 4L, DEMAND, 0.0, 0.0);

        ledger.forgetWorld("world-a");

        assertEquals(1, ledger.settlementCount(), "the unloaded world left no settlement behind");
        assertEquals(parent(), ledger.countedParentMs(), 1.0e-9);
        assertEquals(Long.MIN_VALUE, ledger.epochOf("world-a"));

        WorldResChildLedger.Settlement repeated = ledger.settle(table, "world-a", 4L, DEMAND, 0.0,
            0.0);
        WorldResChildLedger.Settlement older = ledger.settle(table(19L), "world-a", 4L, DEMAND, 0.0,
            0.0);

        assertTrue(repeated.stale());
        assertFalse(repeated.counted());
        assertTrue(older.stale());
        assertEquals(1, ledger.settlementCount(), "a late report of the old epoch is not counted");
        assertEquals(parent(), ledger.countedParentMs(), 1.0e-9);
        assertEquals(2, ledger.staleRejections());
    }

    @Test
    void aReloadedWorldOpensAFreshAllowanceAndStillCountsOnce() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ledger.settle(table(20L), "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.forgetWorld("world-a");
        assertEquals(0, ledger.settlementCount());
        assertEquals(0.0, ledger.countedParentMs(), 0.0);

        WorldResChildLedger.Settlement reloaded = ledger.settle(table(21L), "world-a", 5L, DEMAND,
            0.0, 0.0);

        assertTrue(reloaded.counted());
        assertEquals(1, ledger.settlementCount());
        assertEquals(5L, ledger.epochOf("world-a"));
        assertEquals(parent(), ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void aResetClearsSettlementsEpochsAndCounters() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ledger.settle(table(20L), "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.settle(table(20L), "world-b", 4L, DEMAND, 0.0, 0.0);
        ledger.settle(table(19L), "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.forgetWorld("world-b");

        ledger.reset();

        assertEquals(0, ledger.settlementCount());
        assertEquals(0.0, ledger.countedParentMs(), 0.0);
        assertEquals(OPENING, ledger.epoch());
        assertEquals(Long.MIN_VALUE, ledger.epochOf("world-a"));
        assertEquals(0, ledger.staleRejections());
        assertEquals(0, ledger.evictions());
        assertEquals(0, ledger.trackedWorlds());

        WorldResChildLedger.Settlement fresh = ledger.settle(table(20L), "world-a", 9L, DEMAND,
            0.0, 0.0);
        assertTrue(fresh.counted());
        assertEquals(9L, ledger.epochOf("world-a"));
        assertEquals(parent(), ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void theTickRetentionBoundDropsOldSettlementsAndCountedFollows() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        for (long tick = 1L; tick <= 200L; tick++) {
            ledger.settle(table(tick), "world-a", 4L, DEMAND, 0.0, 0.0);
        }

        long retained = WorldResChildLedger.RETENTION_TICKS + 1L;
        assertEquals(retained, ledger.settlementCount());
        assertEquals(retained * parent(), ledger.countedParentMs(), 1.0e-9);
        assertEquals(200L - retained, ledger.evictions());
    }

    @Test
    void theWorldBoundKeepsTheTrackedStateBounded() {
        List<String> worlds = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            worlds.add("world-" + index);
        }
        ShareTable table = tableOf(worlds, 21L);
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        for (int index = 0; index < worlds.size(); index++) {
            ledger.settle(table, worlds.get(index), 10L + index, DEMAND, 0.0, 0.0);
        }

        assertEquals(WorldResChildLedger.MAX_WORLDS, ledger.trackedWorlds());
        assertTrue(ledger.retiredWorlds() <= WorldResChildLedger.MAX_WORLDS);
        assertEquals(ledger.trackedWorlds(), ledger.settlementCount());
        assertEquals(ledger.trackedWorlds() * table.row("world-0", ShareClass.WORLDRES).shareMs(),
            ledger.countedParentMs(), 1.0e-6);
    }

    @Test
    void theSettlementSnapshotNamesChildRowsAndNotTheParentRows() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ledger.settle(table(20L), "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.settle(table(20L), "world-b", 4L, DEMAND, 0.0, 0.0);

        List<WorldResChildBudget> settlements = ledger.settlements();

        assertEquals(2, settlements.size());
        for (WorldResChildBudget budget : settlements) {
            assertEquals(2, budget.childRows().size());
            assertEquals(budget.childShareMs(), budget.childRows().stream()
                .mapToDouble(WorldResChildBudget.ChildRow::shareMs).sum(), 1.0e-9);
            assertTrue(budget.childShareMs() <= budget.parentShareMs() + 1.0e-9);
        }
    }
}
