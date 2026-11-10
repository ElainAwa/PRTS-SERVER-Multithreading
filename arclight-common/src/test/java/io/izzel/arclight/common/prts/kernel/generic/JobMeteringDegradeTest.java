/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobMeteringDegradeTest {

    @Test
    void dedupeAndDegradeEntryAndDefaultOff() {
        JobMetering metering = new JobMetering();
        assertTrue(metering.chargeOnce("job-1", "w", "entity", 1.0));
        assertFalse(metering.chargeOnce("job-1", "w", "entity", 1.0));
        assertTrue(metering.chargeOnce("job-2", "w", "entity", 1.0));
        assertEquals(2.0, metering.usedMs().get("w").get(ShareClass.ENTITY), 1e-9);

        metering.charge("w", "entity", 10_000.0);
        ShareTable table = metering.plan(new SharePlanner(), List.of("w"), 1L, Map.of());
        DegradeLadder off = new DegradeLadder(() -> false);
        assertFalse(metering.noteDegrade(off, table, 1L), "default off does not count effective");
        assertEquals(0L, off.effectiveTotal());

        DegradeLadder on = new DegradeLadder(() -> true);
        assertTrue(metering.noteDegrade(on, table, 1L));
        assertEquals(DegradeLevel.B3, on.sign().deepest());
        assertEquals(1L, on.effectiveTotal());
        for (int i = 0; i < DegradeLadder.DECLARED_RETURN_TICKS; i++) on.noteTick(false);
        assertTrue(on.noteReturned(DegradeLevel.B3));
    }
}
