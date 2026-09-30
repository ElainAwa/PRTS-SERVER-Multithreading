/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The self-proof and the zero value discipline of the per-class timer. */
class SelfTimersTest {

    @BeforeEach
    void clear() {
        SelfTimers.resetAll();
    }

    @Test
    void everyClassHasARowEvenWhenNothingWasSampled() {
        MeterWindow window = SelfTimers.snapshot(10L, 10, false);

        assertEquals(SelfClass.rowCount(), window.rowCount());
        assertEquals(SelfClass.assertedCount(), 7);
        assertEquals(0, window.missingClasses());
        for (SelfClass selfClass : SelfClass.values()) {
            SelfRow row = window.row(selfClass);
            assertNotNull(row, selfClass.name());
            assertEquals(0L, row.totalNanos());
            assertEquals(0.0, row.sharePct());
            assertEquals(0L, row.samples());
        }
    }

    @Test
    void theThreeSelfMonitoringValuesArePublished() {
        for (int index = 0; index < SelfTimers.RING + 10; index++) {
            SelfTimers.note(SelfClass.ENTITY, "world", "region-1", 1_000L);
        }
        SelfTimers.note(SelfClass.OBSERVE, "", "runtime", 2_000_000L);

        MeterWindow window = SelfTimers.consume(20L, 20, false);

        assertEquals(10L, window.lostSamples());
        // the rate covers every class of the window: the entity ring plus the one observation row
        long recorded = SelfTimers.RING + 11L;
        long retained = SelfTimers.RING + 1L;
        assertEquals((double) retained / recorded, window.sampleRate(), 1.0e-9);
        assertEquals(2.0, window.observeMs(), 1.0e-9);
        SelfRow entity = window.row(SelfClass.ENTITY);
        assertNotNull(entity);
        assertEquals(SelfTimers.RING, entity.samples());
        assertEquals(10L, entity.lost());
    }

    @Test
    void thePercentilesArePublishedAsAPair() {
        for (int index = 1; index <= 100; index++) {
            SelfTimers.note(SelfClass.AI, "world", "region-1", index);
        }

        MeterWindow window = SelfTimers.snapshot(1L, 1, false);
        SelfRow row = window.row(SelfClass.AI);

        assertNotNull(row);
        assertEquals(50L, row.p50Nanos());
        assertEquals(99L, row.p99Nanos());
    }

    @Test
    void waitTimeNeverEntersAClassRow() {
        SelfTimers.noteWait("world", "region-1", "chunk", 5_000_000L);

        MeterWindow window = SelfTimers.snapshot(1L, 1, false);

        assertEquals(5.0, window.waitTotalMs(), 1.0e-9);
        for (SelfRow row : window.rows()) {
            assertEquals(0L, row.totalNanos(), row.selfClass().name());
        }
        assertEquals(0.0, window.totalMs(), 1.0e-9);
    }

    @Test
    void theCatchAllRowCarriesTheDifference() {
        SelfTimers.note(SelfClass.ENTITY, "world", "region-1", 3_000_000L);
        SelfTimers.note(SelfClass.OTHER, "world", "region-1", 1_000_000L);

        MeterWindow window = SelfTimers.snapshot(1L, 1, false);

        assertEquals(1.0, window.unclassifiedMs(), 1.0e-9);
        assertEquals(4.0, window.totalMs(), 1.0e-9);
        assertTrue(window.row(SelfClass.ENTITY).sharePct() > 0.0);
    }
}
