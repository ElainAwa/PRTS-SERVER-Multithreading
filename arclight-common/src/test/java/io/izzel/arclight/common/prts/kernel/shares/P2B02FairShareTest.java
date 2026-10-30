/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.StarvationEvent;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two-level fair-share plan, the world-by-class overrun row and the starvation event. */
class P2B02FairShareTest {

    private final SharePlanner planner = new SharePlanner();

    @Test
    void activityWeightsSplitTheWorldShareBeforeTheClassShare() {
        ShareTable table = planner.planFair(List.of("p2b02-a", "p2b02-b"), 1L,
            Map.of("p2b02-a", used(9.0), "p2b02-b", used(9.0)),
            Map.of("p2b02-a", 1.0, "p2b02-b", 3.0));

        // The fair split would be 11 / 33, but the declared per-world cap is 8, so both bind to 8.
        assertEquals(8.0, sumShares(table.rowsOf("p2b02-a")), 1.0e-9);
        assertEquals(8.0, sumShares(table.rowsOf("p2b02-b")), 1.0e-9);
        for (ShareTable.ShareRow row : table.rowsOf("p2b02-a")) {
            assertEquals(row.shareMs(), 8.0 * row.shareClass().weight(), 1.0e-9);
        }
        assertEquals(16.0, table.sumSharesMs(), 1.0e-9);
        assertTrue(planner.checkConservation(table).ok());
    }

    @Test
    void anUnweightedOrSingleWorldPlanFallsBackToTheEqualShare() {
        ShareTable fair = planner.planFair(List.of("p2b02-equal-a", "p2b02-equal-b"), 2L,
            Map.of(), Map.of());
        ShareTable legacy = planner.plan(List.of("p2b02-equal-a", "p2b02-equal-b"), 2L, Map.of());
        double worldShare = KernelSettings.worldShareMs();

        assertEquals(worldShare, sumShares(fair.rowsOf("p2b02-equal-a")), 1.0e-9);
        assertEquals(worldShare, sumShares(fair.rowsOf("p2b02-equal-b")), 1.0e-9);
        assertEquals(legacy, fair);
    }

    @Test
    void zeroWorldsAndZeroUsedValuesStillKeepTheReserveColumn() {
        ShareTable empty = planner.planFair(List.of(), 3L, Map.of(), Map.of());
        assertEquals(0, empty.rows().size());
        assertEquals(KernelSettings.reserveMs(), empty.reserve().reserveMs(), 1.0e-9);

        ShareTable zero = planner.planFair(List.of("p2b02-zero"), 4L, Map.of(), Map.of());
        for (ShareClass shareClass : ShareClass.ASSERTED) {
            assertNotNull(zero.row("p2b02-zero", shareClass));
            assertEquals(0.0, zero.row("p2b02-zero", shareClass).usedMs(), 1.0e-9);
        }
    }

    @Test
    void aWorldByClassOverrunCountsInAllThreeRows() {
        SharePlanner over = new SharePlanner();
        over.noteOverrun(new ShareTable.ShareRow("p2b02-over", ShareClass.ENTITY, 0.0, 1.0),
            "p2b02-site");
        over.noteOverrun(new ShareTable.ShareRow("p2b02-over", ShareClass.AI, 0.0, 1.0),
            "p2b02-site");
        over.noteOverrun(new ShareTable.ShareRow("p2b02-other", ShareClass.ENTITY, 0.0, 1.0),
            "p2b02-site");

        assertEquals(1L, over.dualOverrunCount("p2b02-over", ShareClass.ENTITY));
        assertEquals(1L, over.dualOverrunCount("p2b02-over", ShareClass.AI));
        assertEquals(1L, over.dualOverrunCount("p2b02-other", ShareClass.ENTITY));
        assertEquals(2L, over.classOverrunCount(ShareClass.ENTITY));
        assertEquals(3L, over.worldOverrunTotal());
        assertEquals(3L, over.dualOverrunTotal());
    }

    @Test
    void aStarvedWorldPublishesOneEventThenResetsWhenItGetsAShare() {
        assertNull(planner.observeWorldShare("p2b02-starved", false, 1L, 3));
        assertNull(planner.observeWorldShare("p2b02-starved", false, 2L, 3));
        StarvationEvent event = planner.observeWorldShare("p2b02-starved", false, 3L, 3);
        assertNotNull(event);
        assertEquals(3, event.missedTicks());
        assertTrue(event.forcedYield());
        assertNull(planner.observeWorldShare("p2b02-starved", false, 4L, 3),
            "the event is published once per streak");
        assertEquals(1L, planner.hungerEventCount());

        assertNull(planner.observeWorldShare("p2b02-starved", true, 5L, 3));
        assertEquals(0, planner.hungerStreak("p2b02-starved"));
        planner.observeWorldShare("p2b02-starved", false, 6L, 3);
        planner.observeWorldShare("p2b02-starved", false, 7L, 3);
        assertNotNull(planner.observeWorldShare("p2b02-starved", false, 8L, 3));
        assertEquals(2L, planner.hungerEventCount());
    }

    private static double sumShares(List<ShareTable.ShareRow> rows) {
        double sum = 0.0;
        for (ShareTable.ShareRow row : rows) {
            sum += row.shareMs();
        }
        return sum;
    }

    private static EnumMap<ShareClass, Double> used(double entityMs) {
        EnumMap<ShareClass, Double> used = new EnumMap<>(ShareClass.class);
        used.put(ShareClass.ENTITY, entityMs);
        return used;
    }
}
