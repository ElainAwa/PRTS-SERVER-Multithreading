/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conservation equation, the two states of the budget and the per-class metering. */
class BudgetGovernanceTest {

    @Test
    void theConservationEquationCarriesAllThreeTermsAndItsBands() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(java.util.List.of("world-a", "world-b"), 1L, Map.of());
        ConservationCheck check = planner.checkConservation(table);

        assertEquals(check.sumSharesMs(), table.sumSharesMs(), 1.0e-9);
        assertEquals(check.reserveMs(), table.reserve().reserveMs(), 1.0e-9);
        assertEquals(check.hostOverheadMs(), table.hostOverheadMs(), 1.0e-9);
        assertEquals(check.plannedMs(),
            check.sumSharesMs() + check.reserveMs() + check.hostOverheadMs(), 1.0e-9);
        assertEquals(check.slackMs(), check.eBudgetMs() - check.plannedMs(), 1.0e-9);
        assertEquals(ConservationCheck.Verdict.OK, check.verdict());
        assertTrue(check.ok());

        ConservationCheck critical = planner.checkConservation(
            squeeze(table, check.plannedMs() + 0.5));
        assertEquals(ConservationCheck.Verdict.CRITICAL, critical.verdict());
        assertTrue(critical.ok());

        ConservationCheck over = planner.checkConservation(squeeze(table, check.plannedMs() - 1.0));
        assertEquals(ConservationCheck.Verdict.OVER, over.verdict());
        assertFalse(over.ok());
        assertEquals(1.0, over.overByMs(), 1.0e-9);

        ConservationCheck unplanned = planner.checkConservation(null);
        assertFalse(unplanned.planned());
        assertFalse(unplanned.ok());
        assertEquals("unplanned", unplanned.item());
    }

    @Test
    void theTwoStatesFollowTheirFourConditions() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(java.util.List.of("world"), 1L, Map.of());
        ConservationCheck holds = planner.checkConservation(table);
        ConservationCheck broken = planner.checkConservation(squeeze(table, 1.0));
        BudgetStateMachine machine = new BudgetStateMachine();

        assertEquals(BudgetStateMachine.Phase.NORMAL,
            machine.judge(1L, table, 0L, 0L, holds).phase());
        assertTrue(machine.judge(2L, table, 1L, 0L, holds).degraded());
        assertTrue(machine.judge(3L, table, 0L, 1L, holds).degraded());
        assertTrue(machine.judge(4L, table, 0L, 0L, broken).degraded());
        assertTrue(machine.judge(5L, drawnReserve(table), 0L, 0L, holds).degraded());

        BudgetStateMachine.Decision back = machine.judge(6L, table, 0L, 0L, holds);
        assertEquals(BudgetStateMachine.Phase.NORMAL, back.phase());
        assertEquals("none", back.reason());
        assertEquals(1L, back.enteredCount());
        assertEquals(1L, back.leftCount());
        assertEquals(2L, back.normalTicks());

        BudgetStateMachine.Decision unplanned = machine.judge(7L, null, 0L, 0L, null);
        assertEquals(BudgetStateMachine.Phase.NORMAL, unplanned.phase());
        assertEquals("unplanned", unplanned.reason());
        assertTrue(unplanned.marginsWithin());
    }

    @Test
    void everyShareClassPublishesAMeteredRow() {
        long[] totals = new long[SelfClass.values().length];
        totals[SelfClass.ENTITY.ordinal()] = 2_000_000L;
        totals[SelfClass.AI.ordinal()] = 500_000L;
        ShareMeter.TickReading reading = ShareMeter.read(7L, true, Map.of("world", totals));

        assertEquals(ShareClass.rowCount(), reading.rowCount());
        assertTrue(reading.complete());
        assertTrue(reading.missing().isEmpty());
        assertEquals(2.0, reading.row(ShareClass.ENTITY).usedMs(), 1.0e-9);
        assertEquals(0.5, reading.row(ShareClass.AI).usedMs(), 1.0e-9);
        assertNotNull(reading.row(ShareClass.GRAPH));
        assertEquals(0.0, reading.row(ShareClass.GRAPH).usedMs());
        assertEquals(3, reading.unmapped().size());
        assertTrue(reading.unmapped().contains(SelfClass.OBSERVE));

        Map<String, java.util.EnumMap<ShareClass, Double>> used =
            ShareMeter.usedFromTickTotals(Map.of("world", totals));
        assertEquals(2.0, used.get("world").get(ShareClass.ENTITY), 1.0e-9);
    }

    private static ShareTable squeeze(ShareTable table, double budgetMs) {
        return new ShareTable(table.tickIndex(), table.rows(), table.reserve(),
            table.hostOverheadMs(), budgetMs);
    }

    private static ShareTable drawnReserve(ShareTable table) {
        double drawn = Math.max(1.0, table.reserve().reserveMs());
        return new ShareTable(table.tickIndex(), table.rows(),
            new ShareTable.ReserveRow(drawn, drawn, Map.of()), table.hostOverheadMs(),
            table.eBudgetMs());
    }
}
