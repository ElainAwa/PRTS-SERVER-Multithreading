/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable.ReservePurpose;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline structure of the share table: every asserted class has a row (zero values too),
 * the A-ring reserve is one separate column limited to its two purposes, and the conservation
 * equation can be recomputed from the table alone. */
class P2B01ShareTableTest {

    @Test
    void fiveAssertedClassesHaveRowsWithZeroValuesIncluded() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("p2b01-world"), 1L, Map.of());

        assertEquals(5, ShareClass.ASSERTED.size());
        for (ShareClass shareClass : ShareClass.ASSERTED) {
            ShareTable.ShareRow row = table.row("p2b01-world", shareClass);
            assertNotNull(row, () -> "missing row for " + shareClass);
            assertEquals(0.0, row.usedMs(), 1.0e-9);
            assertTrue(row.shareMs() > 0.0);
        }
        assertEquals(ShareClass.rowCount(), table.rowsOf("p2b01-world").size());
    }

    @Test
    void theConservationEquationCarriesSharesReserveAndHost() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("p2b01-a", "p2b01-b"), 2L, Map.of());
        ConservationCheck check = planner.checkConservation(table);

        assertTrue(check.planned());
        assertEquals(table.sumSharesMs(), check.sumSharesMs(), 1.0e-9);
        assertEquals(table.reserve().reserveMs(), check.reserveMs(), 1.0e-9);
        assertEquals(table.hostOverheadMs(), check.hostOverheadMs(), 1.0e-9);
        assertEquals(check.sumSharesMs() + check.reserveMs() + check.hostOverheadMs(),
            check.plannedMs(), 1.0e-9);
        assertEquals(check.eBudgetMs() - check.plannedMs(), check.slackMs(), 1.0e-9);
    }

    @Test
    void theARingReserveIsSeparateAndLimitedToItsTwoPurposes() {
        SharePlanner planner = new SharePlanner();
        ShareTable before = planner.plan(List.of("p2b01-reserve"), 3L, Map.of());
        double classSharesBefore = before.sumSharesMs();

        planner.consumeReserve(ReservePurpose.FORCED_MATERIALIZE, 1.5);
        planner.consumeReserve(ReservePurpose.MIGRATION_WAIT, 2.0);
        ShareTable after = planner.plan(List.of("p2b01-reserve"), 4L, Map.of());

        assertEquals(classSharesBefore, after.sumSharesMs(), 1.0e-9);
        assertEquals(3.5, after.reserve().usedMs(), 1.0e-9);
        assertEquals(1.5, after.reserve().usedByPurpose()
            .get(ReservePurpose.FORCED_MATERIALIZE), 1.0e-9);
        assertEquals(2.0, after.reserve().usedByPurpose()
            .get(ReservePurpose.MIGRATION_WAIT), 1.0e-9);
        assertEquals(2, ReservePurpose.values().length);
        assertThrows(IllegalArgumentException.class, () -> planner.consumeReserve(null, 1.0));
        assertThrows(IllegalArgumentException.class,
            () -> planner.consumeReserve(ReservePurpose.FORCED_MATERIALIZE, -1.0));
        assertThrows(IllegalArgumentException.class,
            () -> planner.consumeReserve(ReservePurpose.FORCED_MATERIALIZE, Double.NaN));
    }

    @Test
    void anExhaustedReserveIsVisibleAndDegradesRatherThanBorrowingFromClassShares() {
        SharePlanner planner = new SharePlanner();
        ShareTable before = planner.plan(List.of("p2b01-overdraw"), 5L, Map.of());
        double share = before.reserve().reserveMs();
        planner.consumeReserve(ReservePurpose.FORCED_MATERIALIZE, share + 1.0);
        ShareTable after = planner.plan(List.of("p2b01-overdraw"), 6L, Map.of());

        assertTrue(after.reserve().remainingMs() < 0.0);
        assertEquals(before.sumSharesMs(), after.sumSharesMs(), 1.0e-9);
        BudgetStateMachine machine = new BudgetStateMachine();
        BudgetStateMachine.Decision decision = machine.judge(6L, after, 0L, 0L,
            planner.checkConservation(after));
        assertTrue(decision.degraded());
        assertTrue(decision.reason().contains("reserve"));
        assertEquals(DegradeLevel.B5, SharePlanner.levelFor(ShareClass.OTHER));
    }
}
