/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import io.izzel.arclight.common.prts.support.PrtsSelfCosts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** What the two world-driven self cost rows make of a reported span. */
class SelfCostTapTest {

    private PrtsSelfCosts.CostTap previous;

    @AfterEach
    void detach() {
        PrtsSelfCosts.install(previous);
        SelfTimers.resetAll();
    }

    @Test
    void nothingIsRecordedWhileNoTapIsInstalled() {
        previous = PrtsSelfCosts.watcher();
        PrtsSelfCosts.install(null);
        assertFalse(PrtsSelfCosts.installed());
        SelfTimers.resetAll();
        PrtsSelfCosts.entityRow("minecraft:overworld", "Villager", 5_000_000L);
        PrtsSelfCosts.blockEntities("minecraft:overworld", 5_000_000L);
        assertEquals(0L, entityNanos(SelfTimers.consumeTickTotals()));
    }

    @Test
    void anEntityRowAndABlockEntitySpanLandInTheirOwnSelfClass() {
        previous = PrtsSelfCosts.watcher();
        SelfCostTap tap = new SelfCostTap();
        PrtsSelfCosts.install(tap);
        assertSame(tap, PrtsSelfCosts.watcher());
        SelfTimers.resetAll();
        PrtsSelfCosts.entityRow("minecraft:overworld", "Villager", 1_000_000L);
        PrtsSelfCosts.blockEntities("minecraft:overworld", 2_000_000L);
        Map<String, long[]> totals = SelfTimers.consumeTickTotals();
        assertEquals(1_000_000L, entityNanos(totals));
        assertEquals(2_000_000L, blockEntityNanos(totals));
        assertEquals(0L, entityNanos(SelfTimers.consumeTickTotals()));
    }

    private static long entityNanos(Map<String, long[]> totals) {
        return total(totals, SelfClass.ENTITY.ordinal());
    }

    private static long blockEntityNanos(Map<String, long[]> totals) {
        return total(totals, SelfClass.BLOCKENTITY.ordinal());
    }

    private static long total(Map<String, long[]> totals, int index) {
        long sum = 0L;
        for (long[] row : totals.values()) {
            sum += row[index];
        }
        return sum;
    }
}
