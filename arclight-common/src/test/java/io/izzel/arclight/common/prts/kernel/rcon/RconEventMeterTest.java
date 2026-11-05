/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The complete RCON command unit feeds the existing EVENT self timer once, and the unchanged
 * ShareMeter/ShareTable path turns that same sample into the EVENT row. */
class RconEventMeterTest {

    private static final String WORLD = "minecraft:overworld";

    @BeforeEach
    @AfterEach
    void clearTimer() {
        SelfTimers.resetAll();
    }

    private static long eventNanos() {
        long[] row = SelfTimers.consumeTickTotals().get(WORLD);
        return row == null ? 0L : row[SelfClass.EVENT.ordinal()];
    }

    @Test
    void commandBeforeIsZeroAndAfterIsPositiveInTheExistingEventTimer() {
        assertEquals(0L, eventNanos());
        RconEventMeter.note(WORLD, System.nanoTime() - 1_000_000L, true);
        assertTrue(eventNanos() > 0L);
    }

    @Test
    void normalAndDisallowedUnitsDoNotMiscountTheEventRow() {
        RconEventMeter.note(WORLD, System.nanoTime() - 1_000_000L, false);
        RconEventMeter.note("", System.nanoTime() - 1_000_000L, true);
        assertEquals(0L, eventNanos());
    }

    @Test
    void theMeteredEventWorkReachesTheShareMeterAndTheRealB4Gate() {
        RconEventMeter.note(WORLD, System.nanoTime() - 1_000_000L, true);
        Map<String, long[]> totals = SelfTimers.consumeTickTotals();
        Map<String, EnumMap<ShareClass, Double>> used = ShareMeter.usedFromTickTotals(totals);
        assertEquals(DegradeLevel.B4, SharePlanner.levelFor(ShareClass.EVENT));
        assertTrue(used.get(WORLD).get(ShareClass.EVENT) > 0.0);
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(SharePlanner.levelFor(ShareClass.EVENT), 1L);
        assertEquals(DegradeLevel.B4, ladder.sign().deepest());

        Map<String, EnumMap<ShareClass, Double>> over = new LinkedHashMap<>();
        EnumMap<ShareClass, Double> event = new EnumMap<>(ShareClass.class);
        event.put(ShareClass.EVENT, 1_000.0);
        over.put(WORLD, event);
        ShareTable table = new SharePlanner().plan(List.of(WORLD), 1L, over);
        assertTrue(table.row(WORLD, ShareClass.EVENT).overrun());
        assertTrue(RconCommandGate.shouldDefer(table, DegradeLevel.B4, true, true));
    }

    @Test
    void aCancelledOrFailedUnitStillCountsExactlyOnceThroughTheSingleNoteCall() {
        long start = System.nanoTime() - 1_000_000L;
        RconEventMeter.note(WORLD, start, true);
        assertEquals(1, countSamples(SelfTimers.consumeTickTotals()));
        assertEquals(0, countSamples(SelfTimers.consumeTickTotals()));
    }

    private static int countSamples(Map<String, long[]> totals) {
        long[] row = totals.get(WORLD);
        return row == null || row[SelfClass.EVENT.ordinal()] == 0L ? 0 : 1;
    }
}
