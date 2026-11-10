/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.generic.JobMetering;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldSetReadout;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I8FrozenCounterTableTest {

    record Counter(String key, String zero, String window, String dimension, boolean literal) {
    }

    private static List<Counter> manifest() {
        return List.of(
            new Counter("wait.cap_defined", "0=no bound", "tick", "global", true),
            new Counter("degrade.level_rows", "0 rows", "tick", "global", true),
            new Counter("budget.reserve_row", "0 ms", "tick", "global", true),
            new Counter("self.timer_rows", "0 rows", "tick", "class", true),
            new Counter("wait.rows", "0 rows", "tick", "wait-site", false),
            new Counter("budget.share_rows", "0 asserted rows", "tick", "world x class", false));
    }

    @Test
    void manifestKeysAreUniqueAndUnknownSemanticsMarked() {
        List<Counter> counters = manifest();
        assertEquals(counters.size(), new HashSet<>(counters.stream().map(Counter::key).toList()).size());
        assertTrue(counters.stream().allMatch(c -> !c.key().isBlank()));
        assertFalse(counters.stream().allMatch(Counter::literal), "some keys are derived/qualified");
        long unknown = counters.stream().filter(c -> "unknown".equals(c.zero())).count();
        assertEquals(0, unknown);
    }

    @Test
    void perRegionIsolationReadoutAndDefaultOff() {
        SlowRegionIsolation configured = new SlowRegionIsolation(true, 3);
        SlowRegionIsolation.Decision slow = configured.observe("r1",
            new SlowRegionIsolation.Region(100.0, 200.0, 5L));
        SlowRegionIsolation.Decision fast = configured.observe("r2",
            new SlowRegionIsolation.Region(100.0, 50.0, 5L));
        assertTrue(slow.isolated());
        assertTrue(slow.writeDeferred());
        assertFalse(fast.isolated());
        assertEquals(1L, configured.skipped());
        assertEquals(0L, configured.barrierWaitCount(), "no server-wide barrier");

        SlowRegionIsolation off = new SlowRegionIsolation();
        SlowRegionIsolation.Decision disabled = off.observe("r1",
            new SlowRegionIsolation.Region(100.0, 200.0, 5L));
        assertFalse(disabled.isolated());
        assertFalse(disabled.writeDeferred());
        assertEquals(0L, off.skipped());
    }

    @Test
    void perWorldReadoutAndDefaultOffMetering() {
        WorldSetReadout readout = new WorldSetReadout();
        readout.observe(List.of("w1", "w2"), List.of(), List.of());
        assertEquals(2L, readout.worldCount());
        assertEquals(0L, readout.setChangeEvents(), "first observation is baseline");

        JobMetering metering = new JobMetering();
        assertTrue(metering.usedMs().isEmpty());
        ShareTable table = metering.plan(new SharePlanner(), List.of("w1"), 1L, Map.of());
        assertEquals(0.0, table.row("w1", ShareClass.ENTITY).usedMs(), 1e-9);
    }
}
