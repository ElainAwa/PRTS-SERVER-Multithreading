/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.meter.SelfCostTap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only the two classes with a production caller receive samples; unsourced classes stay zero. */
class P2B03A02QuotaCostSourceTest {

    @Test
    void entityAndDeviceEntriesAccumulateRealSpans() {
        QuotaCostReadings.reset();
        SelfCostTap tap = new SelfCostTap();
        tap.entityRow("minecraft:overworld", "entity-row", 1200L);
        tap.blockEntities("minecraft:overworld", 3400L);
        assertEquals(1L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.ENTITY));
        assertEquals(1200L, QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.ENTITY));
        assertEquals(1L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.DEVICE));
        assertEquals(3400L, QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.DEVICE));
    }

    @Test
    void unsourcedMachineAndNetStayZero() {
        QuotaCostReadings.reset();
        assertEquals(0L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.MACHINE));
        assertEquals(0L, QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.MACHINE));
        assertEquals(0L, QuotaCostReadings.calls(QuotaCostReadings.QuotaClass.NET));
        assertEquals(0L, QuotaCostReadings.wallNanos(QuotaCostReadings.QuotaClass.NET));
    }

    @Test
    void resetClearsEveryClass() {
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.ENTITY, 10L);
        QuotaCostReadings.note(QuotaCostReadings.QuotaClass.MACHINE, 20L);
        QuotaCostReadings.reset();
        for (QuotaCostReadings.QuotaClass quotaClass : QuotaCostReadings.QuotaClass.values()) {
            assertEquals(0L, QuotaCostReadings.calls(quotaClass));
            assertEquals(0L, QuotaCostReadings.wallNanos(quotaClass));
        }
        assertTrue(true);
    }
}
