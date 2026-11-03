/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-slice world-resource child budget: the real table's WORLDRES row is partitioned into
 * generation, light, the explicit parent other part and the explicit margin, the parent is counted
 * once per world/tick/epoch, an unknown demand is left unknown instead of a fifty-fifty default, and
 * no other share class is touched. */
class WorldResChildBudgetTest {

    private static final long TICK = 11L;
    private static final long EPOCH = 3L;
    private static final WorldResChildBudget.ChildDemand DEMAND =
        WorldResChildBudget.ChildDemand.ofTaskNanos(2_000_000L, 1_000_000L);

    private static ShareTable twoWorlds() {
        return new SharePlanner().plan(List.of("world-a", "world-b"), TICK, Map.of());
    }

    private static double parent(ShareTable table, String world) {
        ShareTable.ShareRow row = table.row(world, ShareClass.WORLDRES);
        assertNotNull(row, "the real table carries the world-resource row");
        return row.shareMs();
    }

    @Test
    void twoWorldsSplitTheirOwnParentRows() {
        ShareTable table = twoWorlds();
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        double sumBefore = table.sumSharesMs();

        WorldResChildLedger.Settlement a = ledger.settle(table, "world-a", EPOCH, DEMAND,
            parent(table, "world-a") * 0.10, parent(table, "world-a") * 0.05);
        WorldResChildLedger.Settlement b = ledger.settle(table, "world-b", EPOCH, DEMAND,
            parent(table, "world-b") * 0.10, parent(table, "world-b") * 0.05);

        assertEquals(WorldResChildBudget.Status.SETTLED, a.budget().status());
        assertEquals(WorldResChildBudget.Status.SETTLED, b.budget().status());
        for (String world : List.of("world-a", "world-b")) {
            WorldResChildBudget budget = ledger.settlementCount() == 2
                ? (world.equals("world-a") ? a.budget() : b.budget()) : null;
            assertEquals(parent(table, world), budget.parentShareMs(), 1.0e-9);
            assertEquals(2.0, budget.genShareMs() / budget.lightShareMs(), 1.0e-9);
            assertTrue(budget.conserved());
            assertTrue(budget.withinParent());
            assertEquals(budget.parentShareMs(), budget.plannedShareMs(), 1.0e-6);
        }
        assertEquals(sumBefore, table.sumSharesMs(), 1.0e-9, "the parent table is not rewritten");
        assertEquals(2, ledger.settlementCount());
        assertEquals(parent(table, "world-a") + parent(table, "world-b"),
            ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void simultaneousWorldsDoNotShareOneAllowance() {
        ShareTable table = twoWorlds();
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        double other = parent(table, "world-a") * 0.1;
        double margin = parent(table, "world-a") * 0.1;

        WorldResChildLedger.Settlement a = ledger.settle(table, "world-a", EPOCH, DEMAND,
            other, margin);
        WorldResChildLedger.Settlement b = ledger.settle(table, "world-b", EPOCH, DEMAND,
            other, margin);

        assertEquals(parent(table, "world-a"), a.budget().parentShareMs(), 1.0e-9);
        assertEquals(parent(table, "world-b"), b.budget().parentShareMs(), 1.0e-9);
        assertTrue(a.budget().childShareMs() <= a.budget().parentShareMs());
        assertTrue(b.budget().childShareMs() <= b.budget().parentShareMs());
        assertEquals(2, ledger.settlementCount(), "two worlds count as two parent rows");
    }

    @Test
    void aKnownZeroDemandKeepsTheParentUnallocated() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-a");
        WorldResChildBudget budget = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(0L, 0L), parent * 0.1, parent * 0.1);

        assertEquals(WorldResChildBudget.Status.SETTLED, budget.status());
        assertEquals(WorldResChildBudget.SplitSource.KNOWN_ZERO_DEMAND, budget.splitSource());
        assertEquals(0.0, budget.genShareMs(), 1.0e-12);
        assertEquals(0.0, budget.lightShareMs(), 1.0e-12);
        assertEquals(parent * 0.8, budget.unallocatedMs(), 1.0e-9);
        assertTrue(budget.conserved());
    }

    @Test
    void anExcessOtherPartIsReportedWithoutNegativeChildren() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-a");
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        WorldResChildLedger.Settlement settlement = ledger.settle(table, "world-a", EPOCH, DEMAND,
            parent * 2.0, 0.0);

        assertEquals(WorldResChildBudget.Status.PARENT_OVER, settlement.budget().status());
        assertEquals(0.0, settlement.budget().genShareMs(), 1.0e-12);
        assertEquals(0.0, settlement.budget().lightShareMs(), 1.0e-12);
        assertEquals(0.0, settlement.budget().unallocatedMs(), 1.0e-12);
        assertFalse(settlement.budget().withinParent());
        assertFalse(settlement.budget().conserved());
        assertEquals(parent, ledger.countedParentMs(), 1.0e-9,
            "the parent row is counted once even when the declared part exceeds it");
    }

    @Test
    void theMarginStaysInsideTheParent() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-b");
        WorldResChildBudget budget = WorldResChildBudget.settle(table, "world-b", EPOCH, DEMAND,
            parent * 0.1, parent * 0.3);

        assertEquals(parent * 0.3, budget.marginMs(), 1.0e-9);
        assertEquals(0.0, budget.unallocatedMs(), 1.0e-9);
        assertEquals(parent, budget.genShareMs() + budget.lightShareMs() + budget.otherMs()
            + budget.marginMs(), 1.0e-6);
        assertTrue(budget.withinParent());
        assertTrue(budget.conserved());
    }

    @Test
    void unknownDemandIsNotReplacedByAFiftyFiftySplit() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-a");
        WorldResChildBudget budget = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.unknown(), 0.0, 0.0);

        assertEquals(WorldResChildBudget.Status.UNKNOWN_DEMAND, budget.status());
        assertEquals(WorldResChildBudget.SplitSource.DEMAND_UNKNOWN, budget.splitSource());
        assertEquals(0.0, budget.genShareMs(), 1.0e-12);
        assertEquals(0.0, budget.lightShareMs(), 1.0e-12);
        assertEquals(parent, budget.unallocatedMs(), 1.0e-9);
        assertFalse(budget.genShareMs() == parent / 2.0 && budget.lightShareMs() == parent / 2.0);
    }

    @Test
    void nanAndNegativeInputsAreInvalid() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-a");

        WorldResChildBudget nanDemand = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(Double.NaN, 1.0), 0.0, 0.0);
        assertEquals(WorldResChildBudget.Status.INVALID_INPUT, nanDemand.status());
        assertEquals(0.0, nanDemand.childShareMs(), 1.0e-12);

        WorldResChildBudget negativeDemand = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(-1.0, 2.0), 0.0, 0.0);
        assertEquals(WorldResChildBudget.Status.INVALID_INPUT, negativeDemand.status());

        WorldResChildBudget negativeMargin = WorldResChildBudget.settle(table, "world-a", EPOCH,
            DEMAND, 0.0, -0.1);
        assertEquals(WorldResChildBudget.Status.INVALID_INPUT, negativeMargin.status());

        WorldResChildBudget nanOther = WorldResChildBudget.settle(table, "world-a", EPOCH,
            DEMAND, Double.NaN, 0.0);
        assertEquals(WorldResChildBudget.Status.INVALID_INPUT, nanOther.status());
        assertEquals(parent, parent(table, "world-a"), 1.0e-9);
    }

    @Test
    void anEpochResetOpensAFreshAllowance() {
        ShareTable table = twoWorlds();
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        WorldResChildLedger.Settlement first = ledger.settle(table, "world-a", EPOCH, DEMAND,
            0.0, 0.0);
        WorldResChildLedger.Settlement duplicate = ledger.settle(table, "world-a", EPOCH, DEMAND,
            0.0, 0.0);

        assertTrue(first.counted());
        assertFalse(duplicate.counted());
        assertTrue(duplicate.duplicate());
        assertSame(first.budget(), duplicate.budget());
        assertEquals(parent(table, "world-a"), ledger.countedParentMs(), 1.0e-9);

        long nextEpoch = EPOCH + 1L;
        WorldResChildLedger.Settlement next = ledger.settle(table, "world-a", nextEpoch, DEMAND,
            0.0, 0.0);
        assertTrue(next.counted());
        assertEquals(nextEpoch, ledger.epoch());
        assertEquals(1, ledger.settlementCount(), "the old epoch settlements were dropped");
        assertEquals(parent(table, "world-a"), ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void theParentRowIsNeverCountedTwice() {
        ShareTable table = twoWorlds();
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        ledger.settle(table, "world-a", EPOCH, DEMAND, 0.0, 0.0);
        WorldResChildLedger.Settlement duplicate = ledger.settle(table, "world-a", EPOCH, DEMAND,
            0.0, 0.0);

        assertTrue(duplicate.duplicate());
        assertFalse(duplicate.counted());
        assertEquals(1, ledger.settlementCount());
        assertEquals(parent(table, "world-a"), ledger.countedParentMs(), 1.0e-9);
        assertEquals(2, duplicate.budget().childRows().size(), "the parent row is not a child row");
        assertEquals(duplicate.budget().childShareMs(),
            duplicate.budget().childRows().stream().mapToDouble(WorldResChildBudget.ChildRow::shareMs)
                .sum(), 1.0e-9);
    }

    @Test
    void taskNanosAreTheOnlySplitBasisAndTheRatioIsScaleFree() {
        ShareTable table = twoWorlds();
        double parent = parent(table, "world-a");
        WorldResChildBudget one = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(2_000_000L, 1_000_000L),
            parent * 0.1, parent * 0.1);
        WorldResChildBudget scaled = WorldResChildBudget.settle(table, "world-a", EPOCH,
            WorldResChildBudget.ChildDemand.ofTaskNanos(200_000_000L, 100_000_000L),
            parent * 0.1, parent * 0.1);

        assertEquals(one.genShareMs(), scaled.genShareMs(), 1.0e-12);
        assertEquals(one.lightShareMs(), scaled.lightShareMs(), 1.0e-12);
        assertEquals(one.genTaskNanos(), 2_000_000L, 1.0e-9);
        assertEquals(scaled.genTaskNanos(), 200_000_000L, 1.0e-9);
    }

    @Test
    void theOtherShareClassesAreNotSqueezed() {
        java.util.EnumMap<ShareClass, Double> used = new java.util.EnumMap<>(ShareClass.class);
        used.put(ShareClass.ENTITY, 10.0);
        used.put(ShareClass.AI, 10.0);
        ShareTable table = new SharePlanner().plan(List.of("world-a"), TICK,
            Map.of("world-a", used));
        ShareTable.ShareRow entity = table.row("world-a", ShareClass.ENTITY);
        ShareTable.ShareRow ai = table.row("world-a", ShareClass.AI);

        WorldResChildBudget budget = WorldResChildBudget.settle(table, "world-a", EPOCH, DEMAND,
            0.0, 0.0);

        assertTrue(budget.childShareMs() <= budget.parentShareMs() + 1.0e-9);
        assertEquals(entity, table.row("world-a", ShareClass.ENTITY));
        assertEquals(ai, table.row("world-a", ShareClass.AI));
        assertEquals(10.0, entity.usedMs(), 1.0e-9);
        assertEquals(10.0, ai.usedMs(), 1.0e-9);
    }

    @Test
    void aMissingWorldResRowIsReportedNotInvented() {
        ShareTable.ShareRow onlyEntity = new ShareTable.ShareRow("world-a", ShareClass.ENTITY,
            1.0, 0.0);
        ShareTable table = new ShareTable(TICK, List.of(onlyEntity),
            new ShareTable.ReserveRow(0.0, 0.0, Map.of()), 0.0, 100.0);
        WorldResChildLedger ledger = new WorldResChildLedger(EPOCH);
        WorldResChildLedger.Settlement settlement = ledger.settle(table, "world-a", EPOCH,
            DEMAND, 0.0, 0.0);

        assertEquals(WorldResChildBudget.Status.NO_PARENT, settlement.budget().status());
        assertEquals(0.0, settlement.budget().parentShareMs(), 1.0e-12);
        assertEquals(0.0, settlement.budget().childShareMs(), 1.0e-12);
        assertEquals(0.0, ledger.countedParentMs(), 1.0e-12);
    }
}
