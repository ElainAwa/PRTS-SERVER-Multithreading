/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only a main-thread synchronous load of an absent chunk is a cold load; async and loaded reads
 * are not. The counter is internal until a controlled window proves a positive count. */
class P2ACC06UnloadedReadTest {

    @Test
    void onlyMainThreadLoadOfAbsentChunkCounts() {
        assertTrue(UnloadedReadReadings.shouldCount(true, true, false));
        assertFalse(UnloadedReadReadings.shouldCount(false, true, false));
        assertFalse(UnloadedReadReadings.shouldCount(true, false, false));
        assertFalse(UnloadedReadReadings.shouldCount(true, true, true));
    }

    @Test
    void counterAddsAndResets() {
        UnloadedReadReadings.reset();
        UnloadedReadReadings.noteColdLoad();
        UnloadedReadReadings.noteColdLoad();
        assertEquals(2L, UnloadedReadReadings.coldLoads());
        UnloadedReadReadings.reset();
        assertEquals(0L, UnloadedReadReadings.coldLoads());
    }
}
