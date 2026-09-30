/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conservation, the two overrun dimensions and the zero-action rule of the share table. */
class SharePlannerTest {

    @Test
    void conservationHoldsAndANegativeMarginStaysRaw() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world"), 1L, used(10.0));

        ShareTable.ShareRow row = table.row("world", ShareClass.ENTITY);
        assertNotNull(row);
        assertTrue(row.marginMs() < 0.0);
        assertEquals(planner.checkConservation(table).ok(), table.sumSharesMs()
            + table.reserve().reserveMs() + table.hostOverheadMs() <= table.eBudgetMs());
        assertTrue(planner.checkConservation(table).ok());
    }

    @Test
    void overrunsAreCountedInBothDimensionsAndNeverExecuted() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world-a", "world-b"), 1L, used(10.0));
        ShareTable.ShareRow overA = table.row("world-a", ShareClass.ENTITY);
        ShareTable.ShareRow overB = table.row("world-b", ShareClass.AI);

        OverrunRecord recordA = planner.record(overA, "site:a", 1L);
        OverrunRecord recordB = planner.record(overB, "site:b", 1L);
        planner.recordWouldDegrade(recordA);
        planner.recordWouldDegrade(recordB);

        assertEquals(2L, planner.classOverrunTotal());
        assertEquals(2L, planner.worldOverrunTotal());
        assertEquals(DegradeLevel.B3, recordA.wouldDegradeLevel());
        assertEquals(DegradeLevel.B1, recordB.wouldDegradeLevel());
        assertFalse(planner.anyActionExecuted());
        for (OverrunRecord record : planner.records()) {
            assertFalse(record.actionExecuted());
        }
        assertEquals(2, planner.records().size());
    }

    @Test
    void theReserveIsASingleColumnAndBorrowingIsCounted() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world"), 1L, Map.of());

        assertEquals(0.0, table.reserve().usedMs());
        assertEquals(KernelSettings.reserveMs(), table.reserve().remainingMs(), 1.0e-9);
        planner.consumeReserve(ReservePurpose.FORCED_MATERIALIZE, 1.5);
        ShareTable after = planner.plan(List.of("world"), 2L, Map.of());
        assertEquals(1.5, after.reserve().usedMs(), 1.0e-9);
        assertEquals(1.5, after.reserve().usedByPurpose()
            .get(ReservePurpose.FORCED_MATERIALIZE), 1.0e-9);
        assertEquals(0L, planner.reserveBorrowedCount());
        planner.noteReserveBorrowed(1.0);
        assertEquals(1L, planner.reserveBorrowedCount());
    }

    @Test
    void theWeightsSplitOneWorldShareExactly() {
        double sum = 0.0;
        for (ShareClass shareClass : ShareClass.values()) {
            sum += shareClass.weight();
        }

        assertEquals(1.0, sum, 1.0e-9);
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world"), 1L, Map.of());
        assertEquals(KernelSettings.worldShareMs(), table.sumSharesMs(), 1.0e-9);
    }

    @Test
    void planningIsDeterministicAndCarriesEveryRow() {
        SharePlanner planner = new SharePlanner();
        ShareTable first = planner.plan(List.of("world"), 7L, used(1.0));
        ShareTable second = planner.plan(List.of("world"), 7L, used(1.0));

        assertEquals(first, second);
        assertEquals(ShareClass.rowCount(), first.rowsOf("world").size());
        assertEquals(ShareClass.rowCount() * 1, first.rows().size());
    }

    @Test
    void anOverrunInsideTheShareIsNotRecorded() {
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world"), 1L, used(0.1));

        assertNull(planner.record(table.row("world", ShareClass.GRAPH), "site:a", 1L));
        assertEquals(0L, planner.classOverrunTotal());
        assertEquals(0L, planner.worldOverrunTotal());
    }

    private static Map<String, EnumMap<ShareClass, Double>> used(double entityMs) {
        EnumMap<ShareClass, Double> row = new EnumMap<>(ShareClass.class);
        row.put(ShareClass.ENTITY, entityMs);
        row.put(ShareClass.AI, entityMs);
        return Map.of("world", row, "world-a", row, "world-b", row);
    }
}
