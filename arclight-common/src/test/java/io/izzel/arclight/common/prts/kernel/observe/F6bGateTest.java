/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class F6bGateTest {

    @Test
    void assertedShareRowsAndSelfTimerRowsMeetGate() {
        assertEquals(5, ShareClass.ASSERTED.size(), "budget.share_rows gate expects 5 asserted");
        assertEquals(8, ShareClass.rowCount(), "support rows remain published separately");
        assertTrue(SelfTimers.timerRows() >= 5, "self.timer_rows gate expects >=5");
    }
}
