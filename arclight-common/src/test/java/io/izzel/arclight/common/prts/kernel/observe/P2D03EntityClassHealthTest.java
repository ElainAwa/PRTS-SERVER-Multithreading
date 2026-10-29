/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-class self health view: three rows, zero values still complete, and a missing row
 * named rather than folded into zero. */
class P2D03EntityClassHealthTest {

    @Test
    void theThreeSelfRowsAggregateIntoAHealthyWindow() {
        SelfTimers.resetAll();
        try {
            SelfTimers.note(SelfClass.ENTITY, "p2d03-world", "batch", 3_000_000L);
            SelfTimers.note(SelfClass.BLOCKENTITY, "p2d03-world", "batch", 2_000_000L);
            SelfTimers.note(SelfClass.AI, "p2d03-world", "batch", 1_000_000L);
            EntityClassHealth.Health health = EntityClassHealth.from(
                SelfTimers.snapshot(1L, 20, false));

            assertEquals(3, health.rows().size());
            assertEquals(3, health.measuredClasses());
            assertTrue(health.complete());
            assertTrue(health.healthy());
            assertTrue(health.missing().isEmpty());
            for (EntityClassHealth.Row row : health.rows()) {
                assertFalse(row.missing());
                assertFalse(row.zero());
                assertTrue(row.totalNanos() > 0L);
            }
        } finally {
            SelfTimers.resetAll();
        }
    }

    @Test
    void anAllZeroWindowIsCompleteAndHealthy() {
        SelfTimers.resetAll();
        try {
            EntityClassHealth.Health health = EntityClassHealth.from(
                SelfTimers.snapshot(2L, 20, false));

            assertTrue(health.complete());
            assertTrue(health.healthy());
            assertEquals(3, health.zero().size());
            assertTrue(health.missing().isEmpty());
            for (EntityClassHealth.Row row : health.rows()) {
                assertTrue(row.zero());
                assertFalse(row.missing());
                assertEquals(0L, row.totalNanos());
            }
        } finally {
            SelfTimers.resetAll();
        }
    }

    @Test
    void aMissingClassIsNamedAndNeverFoldedIntoZero() {
        SelfTimers.MeterWindow window = window(row(SelfClass.ENTITY, 1_000_000L, 0L));
        EntityClassHealth.Health health = EntityClassHealth.from(window);

        assertFalse(health.complete());
        assertFalse(health.healthy());
        assertEquals(2, health.missing().size());
        assertTrue(health.missing().contains(SelfClass.BLOCKENTITY));
        assertTrue(health.missing().contains(SelfClass.AI));
        assertEquals(3, health.rows().size());
        for (EntityClassHealth.Row row : health.rows()) {
            if (health.missing().contains(row.selfClass())) {
                assertTrue(row.missing());
            }
        }
    }

    @Test
    void lostSamplesKeepTheRowsCompleteButTheHealthUnhealthy() {
        SelfTimers.MeterWindow window = window(row(SelfClass.ENTITY, 1_000_000L, 4L),
            row(SelfClass.BLOCKENTITY, 0L, 0L), row(SelfClass.AI, 0L, 0L));
        EntityClassHealth.Health health = EntityClassHealth.from(window);

        assertTrue(health.complete());
        assertFalse(health.healthy());
        assertEquals(4L, health.lostSamples());
        assertTrue(health.missing().isEmpty());
    }

    @Test
    void aNullWindowIsACompleteMiss() {
        EntityClassHealth.Health health = EntityClassHealth.from(null);

        assertFalse(health.complete());
        assertFalse(health.healthy());
        assertEquals(3, health.missing().size());
        assertEquals(3, health.rows().size());
        assertEquals(-1L, health.tickIndex());
    }

    private static SelfTimers.SelfRow row(SelfClass selfClass, long totalNanos, long lost) {
        return new SelfTimers.SelfRow(selfClass, totalNanos, 0.0, 0L, 0L,
            totalNanos == 0L ? 0L : 1L, lost);
    }

    private static SelfTimers.MeterWindow window(SelfTimers.SelfRow... rows) {
        return new SelfTimers.MeterWindow(1L, 20, false, List.of(rows),
            EntityClassHealth.CLASSES.size() - rows.length, 1.0, 0L, 0.0, 0.0, 0.0,
            0.0, 0.0, 0L);
    }
}
