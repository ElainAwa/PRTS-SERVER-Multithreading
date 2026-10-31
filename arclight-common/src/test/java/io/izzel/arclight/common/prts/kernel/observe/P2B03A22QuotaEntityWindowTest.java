/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The entity quota window counts a completed non-warm-up window whose entity wall time per
 * segment second is over the declared quota; the occupancy itself is never published. */
class P2B03A22QuotaEntityWindowTest {

    @Test
    void overQuotaCountsOncePerWindow() {
        QuotaEntityWindow.reset();
        QuotaEntityWindow.noteTick(0L, 0L, 2L, 0L, 1);
        QuotaEntityWindow.noteTick(2L, 4_000_000L, 2L, 0L, 1);
        assertEquals(1L, QuotaEntityWindow.overCount());
        QuotaEntityWindow.noteTick(4L, 8_000_000L, 2L, 0L, 1);
        assertEquals(2L, QuotaEntityWindow.overCount());
    }

    @Test
    void highQuotaStaysZero() {
        QuotaEntityWindow.reset();
        QuotaEntityWindow.noteTick(0L, 0L, 2L, 0L, 1000);
        QuotaEntityWindow.noteTick(2L, 4_000_000L, 2L, 0L, 1000);
        assertEquals(0L, QuotaEntityWindow.overCount());
    }

    @Test
    void warmUpWindowIsNotCounted() {
        QuotaEntityWindow.reset();
        QuotaEntityWindow.noteTick(0L, 0L, 2L, 2L, 1);
        QuotaEntityWindow.noteTick(2L, 4_000_000L, 2L, 2L, 1);
        assertEquals(0L, QuotaEntityWindow.overCount());
        QuotaEntityWindow.noteTick(4L, 8_000_000L, 2L, 2L, 1);
        assertEquals(1L, QuotaEntityWindow.overCount());
    }

    @Test
    void resetClearsTheCount() {
        QuotaEntityWindow.noteTick(0L, 0L, 2L, 0L, 1);
        QuotaEntityWindow.noteTick(2L, 4_000_000L, 2L, 0L, 1);
        QuotaEntityWindow.reset();
        assertEquals(0L, QuotaEntityWindow.overCount());
    }
}
