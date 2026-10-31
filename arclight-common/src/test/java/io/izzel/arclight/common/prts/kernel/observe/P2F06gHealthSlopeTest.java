/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The health window publishes four slopes only after warm-up; negative slopes are allowed. */
class P2F06gHealthSlopeTest {

    @Test
    void noWindowBeforeWidthIsComplete() {
        HealthReadings.reset();
        HealthReadings.noteTick(0L, 0L, 0L, 0L, 0L, 3L, 0L);
        HealthReadings.noteTick(1L, 1L, 1L, 1L, 1L, 3L, 0L);
        assertFalse(HealthReadings.published());
    }

    @Test
    void fullWindowPublishesFourSlopes() {
        HealthReadings.reset();
        HealthReadings.noteTick(0L, 0L, 0L, 0L, 0L, 2L, 0L);
        HealthReadings.noteTick(1L, 2L, 2L, 2L, 2L, 2L, 0L);
        HealthReadings.noteTick(2L, 4L, 4L, 4L, 4L, 2L, 0L);
        assertTrue(HealthReadings.published());
        assertEquals(2.0, HealthReadings.entitySlope(), 1.0e-9);
        assertEquals(2.0, HealthReadings.churnSlope(), 1.0e-9);
        assertEquals(2.0, HealthReadings.inflightSlope(), 1.0e-9);
        assertEquals(2.0, HealthReadings.allocSlope(), 1.0e-9);
    }

    @Test
    void warmupWindowDoesNotPublish() {
        HealthReadings.reset();
        HealthReadings.noteTick(0L, 0L, 0L, 0L, 0L, 2L, 2L);
        HealthReadings.noteTick(1L, 1L, 1L, 1L, 1L, 2L, 2L);
        HealthReadings.noteTick(2L, 2L, 2L, 2L, 2L, 2L, 2L);
        assertFalse(HealthReadings.published());
        HealthReadings.noteTick(3L, 3L, 3L, 3L, 3L, 2L, 2L);
        HealthReadings.noteTick(4L, 4L, 4L, 4L, 4L, 2L, 2L);
        assertTrue(HealthReadings.published());
    }

    @Test
    void negativeSlopeIsAllowed() {
        HealthReadings.reset();
        HealthReadings.noteTick(0L, 10L, 10L, 10L, 10L, 2L, 0L);
        HealthReadings.noteTick(1L, 5L, 5L, 5L, 5L, 2L, 0L);
        HealthReadings.noteTick(2L, 0L, 0L, 0L, 0L, 2L, 0L);
        assertTrue(HealthReadings.published());
        assertEquals(-5.0, HealthReadings.entitySlope(), 1.0e-9);
    }
}
