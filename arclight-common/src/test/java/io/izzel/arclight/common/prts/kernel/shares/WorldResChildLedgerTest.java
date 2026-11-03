/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ledger contract of the internal world-resource child settlement: each world keeps its own
 * epoch and tick position, a settlement of one world never clears another, a late tick or a
 * repeated world/tick/epoch does not count the parent again, and the retained settlements are
 * bounded. */
class WorldResChildLedgerTest {

    private static final long OPENING = 3L;
    private static final WorldResChildBudget.ChildDemand DEMAND =
        WorldResChildBudget.ChildDemand.ofTaskNanos(2_000_000L, 1_000_000L);

    private static ShareTable table(long tick) {
        return new SharePlanner().plan(List.of("world-a", "world-b"), tick, Map.of());
    }

    private static double parent() {
        return table(20L).row("world-a", ShareClass.WORLDRES).shareMs();
    }

    @Test
    void twoWorldsKeepTheirOwnEpochs() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ShareTable table = table(20L);

        WorldResChildLedger.Settlement a = ledger.settle(table, "world-a", 5L, DEMAND, 0.0, 0.0);
        WorldResChildLedger.Settlement b = ledger.settle(table, "world-b", 2L, DEMAND, 0.0, 0.0);

        assertTrue(a.counted());
        assertTrue(b.counted(), "the older epoch of another world is not this world's past");
        assertEquals(2, ledger.settlementCount());
        assertEquals(parent() * 2.0, ledger.countedParentMs(), 1.0e-9);
        assertEquals(5L, ledger.epoch());
    }

    @Test
    void aNewerEpochOfOneWorldKeepsTheOtherWorldsSettlements() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ledger.settle(table(20L), "world-a", 4L, DEMAND, 0.0, 0.0);
        ledger.settle(table(20L), "world-b", 5L, DEMAND, 0.0, 0.0);

        WorldResChildLedger.Settlement newer = ledger.settle(table(21L), "world-a", 6L, DEMAND,
            0.0, 0.0);

        assertTrue(newer.counted());
        assertEquals(2, ledger.settlementCount(),
            "the old allowance of world-a was replaced, the settlement of world-b stayed");
        assertEquals(parent() * 2.0, ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void anOlderTickOfTheSameWorldIsStaleAndIsNotRecounted() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        WorldResChildLedger.Settlement accepted = ledger.settle(table(20L), "world-a", 4L, DEMAND,
            0.0, 0.0);
        double counted = ledger.countedParentMs();

        WorldResChildLedger.Settlement late = ledger.settle(table(19L), "world-a", 4L, DEMAND,
            0.0, 0.0);

        assertTrue(accepted.counted());
        assertFalse(late.counted());
        assertEquals(1, ledger.settlementCount());
        assertEquals(counted, ledger.countedParentMs(), 0.0,
            "a late tick of a settled world must not count the parent again");
    }

    @Test
    void theSameWorldTickEpochSettlesOnce() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        ShareTable table = table(20L);
        WorldResChildLedger.Settlement first = ledger.settle(table, "world-a", 4L, DEMAND, 0.0,
            0.0);
        WorldResChildLedger.Settlement duplicate = ledger.settle(table, "world-a", 4L, DEMAND, 0.0,
            0.0);

        assertTrue(first.counted());
        assertFalse(duplicate.counted());
        assertTrue(duplicate.duplicate());
        assertSame(first.budget(), duplicate.budget());
        assertEquals(1, ledger.settlementCount());
        assertEquals(parent(), ledger.countedParentMs(), 1.0e-9);
    }

    @Test
    void theRetainedSettlementsAreBounded() {
        WorldResChildLedger ledger = new WorldResChildLedger(OPENING);
        for (long tick = 1L; tick <= 200L; tick++) {
            ShareTable table = table(tick);
            ledger.settle(table, "world-a", 4L, DEMAND, 0.0, 0.0);
            ledger.settle(table, "world-b", 4L, DEMAND, 0.0, 0.0);
        }

        assertTrue(ledger.settlementCount() <= 256,
            "a long run must not keep an unbounded number of settlements: "
                + ledger.settlementCount());
        assertEquals(256, ledger.settlementCount(),
            "the count bound is what keeps the ledger after 400 settlements");
        assertEquals(ledger.settlementCount() * parent(), ledger.countedParentMs(), 1.0e-6,
            "only the retained settlements count their parent row");
    }
}
