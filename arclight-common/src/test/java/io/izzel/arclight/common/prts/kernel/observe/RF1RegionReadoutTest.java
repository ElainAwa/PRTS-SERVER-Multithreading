/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RF1RegionReadoutTest {

    @Test
    void regionEventsUpdateCriteriaViewAndClear() {
        RegionReadout readout = new RegionReadout(new SlowRegionIsolation(true, 3));
        RegionReadout.State slow = readout.observe("r1",
            new SlowRegionIsolation.Region(100.0, 200.0, 5L));
        readout.observe("r2", new SlowRegionIsolation.Region(100.0, 50.0, 5L));
        assertTrue(slow.isolated());
        assertEquals(2, readout.regions());
        Map<String, Object> view = readout.criteriaView();
        assertTrue(view.containsKey("region.r1.isolated"));
        assertTrue(view.containsKey("region.r2.isolated"));
        assertEquals(0L, view.get("region.barrier_waits"));
        readout.clear();
        assertEquals(0, readout.regions());
        assertFalse(readout.criteriaView().containsKey("region.r1.isolated"));
    }

    @Test
    void manifestLivesInMainAndMarksDerivedKeys() {
        assertEquals(6, FrozenCounterManifest.counters().size());
        assertTrue(FrozenCounterManifest.counters().stream().anyMatch(c -> !c.literal()));
    }
}
