/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The passthrough slot tells an empty slot from a payload that was dropped, and the version slot
 * refuses a write that names a generation the slot does not hold. */
class ArenaPassthroughTest {

    @Test
    void aRoundTripThatKeptThePayloadReportsNoDifference() {
        ArenaPassthrough passthrough = new ArenaPassthrough();

        long generation = passthrough.write("world", new byte[] {7, 8, 9});

        assertEquals(1L, generation);
        assertTrue(passthrough.readBack("world"));
        assertEquals(0L, passthrough.reading().lost());
        assertEquals(0L, passthrough.reading().roundtripDiff());
        assertEquals(1L, passthrough.reading().roundtripPairs());
        assertEquals(1L, passthrough.reading().roundtripEqual());
        assertEquals(3L, passthrough.reading().bytesKept());
    }

    @Test
    void asecondWriteMovesTheGenerationAndADroppedPassthroughIsCounted() {
        ArenaPassthrough passthrough = new ArenaPassthrough();

        passthrough.write("world", new byte[] {1});
        passthrough.write("world", new byte[] {2, 3});

        assertEquals(2L, passthrough.generation("world"));
        assertEquals(2L, passthrough.reading().written());
        assertEquals(1L, passthrough.drop("world"));
        assertEquals(1L, passthrough.reading().lost());
        assertTrue(passthrough.states().get("world").held());
    }

    @Test
    void aWriteThatNamesAStaleGenerationIsRefusedAndCounted() {
        ArenaPassthrough passthrough = new ArenaPassthrough();
        long generation = passthrough.write("world", new byte[] {5});

        assertFalse(passthrough.publish("world", generation + 1L));
        assertTrue(passthrough.publish("world", generation));
        assertEquals(2L, passthrough.reading().versionChecks());
        assertEquals(1L, passthrough.reading().versionPublishes());
        assertEquals(1L, passthrough.reading().versionMismatch());
    }

    @Test
    void areadOfAnEmptySlotIsNotADifference() {
        ArenaPassthrough passthrough = new ArenaPassthrough();

        assertFalse(passthrough.readBack("never-written"));

        assertEquals(0L, passthrough.reading().roundtripPairs());
        assertEquals(0L, passthrough.reading().roundtripDiff());
        assertEquals(1L, passthrough.reading().read());
    }
}
