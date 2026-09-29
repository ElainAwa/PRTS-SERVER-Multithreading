/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The counter readout of the mod interoperability layer: a counter that fired reads back what it
 * counted, and reading a counter that never fired answers zero instead of creating one.
 */
class PrtsModSupportStatsTest {

    @Test
    void countsTheEventsItIsGiven() {
        PrtsModSupportStats.count("test-single");
        PrtsModSupportStats.count("test-batch", 4L);

        assertEquals(1L, PrtsModSupportStats.read("test-single"));
        assertEquals(4L, PrtsModSupportStats.read("test-batch"));
    }

    @Test
    void ignoresDeltasThatAddNothing() {
        PrtsModSupportStats.count("test-zero", 0L);
        PrtsModSupportStats.count("test-negative", -3L);

        assertEquals(0L, PrtsModSupportStats.read("test-zero"));
        assertEquals(0L, PrtsModSupportStats.read("test-negative"));
    }

    @Test
    void readsAnUnknownCounterAsZero() {
        assertEquals(0L, PrtsModSupportStats.read("test-never-fired"));
    }
}
