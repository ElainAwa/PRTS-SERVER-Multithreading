/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.sites.WorldSetReadout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I8DualExitProductionViewTest {

    @Test
    void productionViewKeepsControlAndCriteriaSeparate() {
        KernelReadings.DualExitView clean = KernelReadings.dualExitView(
            Map.of("control.only", 1), Map.of("criteria.only", 1));
        assertTrue(clean.clean());
        KernelReadings.DualExitView collision = KernelReadings.dualExitView(
            Map.of("budget.share_rows", 5), Map.of("budget.share_rows", 5));
        assertFalse(collision.clean(), "same key in both sections is visible failure");
        assertTrue(KernelReadings.DualExitView.empty().clean());
    }

    @Test
    void regionReadoutObjectAndEmptyDefaultState() {
        WorldSetReadout readout = new WorldSetReadout();
        readout.observe(List.of("w1", "w2"), List.of(), List.of());
        assertEquals(2L, readout.worldCount());
        assertEquals(0L, readout.setChangeEvents(), "first observation is baseline");
        assertEquals(0L, new WorldSetReadout().worldCount(), "default empty state");
    }
}
