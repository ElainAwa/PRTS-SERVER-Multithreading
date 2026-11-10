/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitTraceTest {

    @Test
    void threeCapacitiesSelfCheckAndWorldId() {
        CommitTrace trace = new CommitTrace(1, 2, 1);
        assertTrue(trace.record(1L, 0, "w1"));
        assertTrue(trace.record(1L, 1, "w1"));
        assertFalse(trace.record(2L, 2, "w2"), "plan/seat/world capacity exceeded");
        assertTrue(trace.selfCheck());
        assertEquals(2L, trace.recorded());
        assertEquals(1L, trace.dropped());
        assertEquals(1, trace.worlds().size());
        assertTrue(trace.worlds().contains("w1"));
    }
}
