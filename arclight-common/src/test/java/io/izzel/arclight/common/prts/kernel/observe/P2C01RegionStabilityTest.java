/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline stability window: a partition has to keep one identity for the declared window
 * before it may be adopted; a different identity starts a new window; absent reads never count. */
class P2C01RegionStabilityTest {

    private static final long[] SQUARE = chunks(new int[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}});
    private static final long[] APART = chunks(new int[][] {{0, 0}, {20, 20}});

    @Test
    void theSameIdentityBecomesStableExactlyAtTheWindowBoundary() {
        RegionStability stability = new RegionStability();
        String hash = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("p2c01-world", SQUARE)));

        RegionStability.Decision first = stability.observe(hash, 0L);
        assertTrue(first.available());
        assertFalse(first.changed());
        assertEquals(1L, first.stableTicks());
        for (long tick = 1L; tick < 19L; tick++) {
            stability.observe(hash, tick);
        }
        assertEquals(19L, stability.stableTicks());
        assertFalse(stability.stable(RegionIdentityObserver.STABILITY_WINDOW_TICKS));

        stability.observe(hash, 19L);
        assertEquals(20L, stability.stableTicks());
        assertTrue(stability.stable(RegionIdentityObserver.STABILITY_WINDOW_TICKS));
        assertEquals(0L, stability.changes());
    }

    @Test
    void aDifferentIdentityStartsANewWindowAndIsCountedAsAChange() {
        RegionStability stability = new RegionStability();
        String square = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("p2c01-world", SQUARE)));
        String apart = RegionIdentityObserver.identityHash(List.of(
            new RegionIdentityObserver.WorldChunks("p2c01-world", APART)));
        assertNotEquals(square, apart);

        stability.observe(square, 0L);
        stability.observe(square, 1L);
        RegionStability.Decision changed = stability.observe(apart, 2L);
        assertTrue(changed.changed());
        assertEquals(1L, changed.stableTicks());
        assertEquals(1L, stability.changes());
        assertFalse(stability.stable(RegionIdentityObserver.STABILITY_WINDOW_TICKS));

        for (long tick = 3L; tick <= 21L; tick++) {
            stability.observe(apart, tick);
        }
        assertTrue(stability.stable(RegionIdentityObserver.STABILITY_WINDOW_TICKS));
        assertEquals(1L, stability.changes());
    }

    @Test
    void absentOrEmptyIdentityNeverBecomesStableAndZeroValuesStayZero() {
        RegionStability stability = new RegionStability();
        assertFalse(stability.available());
        assertEquals(0L, stability.stableTicks());
        assertEquals(0L, stability.changes());

        RegionStability.Decision blank = stability.observe(" ", 3L);
        assertFalse(blank.available());
        assertEquals("-", blank.identityHash());
        assertFalse(stability.stable(1));

        RegionStability.Decision missing = stability.observe(null, 4L);
        assertFalse(missing.available());
        assertEquals(0L, stability.stableTicks());
        assertFalse(stability.stable(1));
    }

    @Test
    void theObserverWithoutASourcePublishesZeroAndDoesNotPretendToBeLive() {
        RegionIdentityObserver observer = new RegionIdentityObserver();
        RegionIdentityObserver.Reading reading = observer.read(1L);

        assertEquals(0, reading.available());
        assertEquals(0, reading.count());
        assertEquals("-", reading.identityHash());
        assertEquals(0, reading.chunks());
        assertEquals(0, reading.worlds());
        assertEquals(0L, observer.skip());
        assertEquals(0L, observer.barrierWaitCount());
        assertEquals(0L, observer.divisionsApplied());
        assertEquals("none", RegionIdentityObserver.BARRIER_WAIT_SOURCE);
        assertEquals(RegionIdentityObserver.STABILITY_WINDOW_TICKS, observer.stabilityWindowTicks());
        assertEquals(RegionIdentityObserver.ROLLBACK_GATE_TICKS, observer.rollbackGateTicks());
    }

    @Test
    void theOfflineWindowUsesThePublishedRollbackGate() {
        RegionStability stability = new RegionStability();
        assertEquals(RegionIdentityObserver.ROLLBACK_GATE_TICKS, stability.rollbackGateTicks());
        assertEquals(20, RegionIdentityObserver.STABILITY_WINDOW_TICKS);
        assertEquals(20, RegionIdentityObserver.ROLLBACK_GATE_TICKS);
    }

    private static long[] chunks(int[][] coordinates) {
        long[] packed = new long[coordinates.length];
        for (int index = 0; index < coordinates.length; index++) {
            packed[index] = RegionIdentityObserver.pack(coordinates[index][0], coordinates[index][1]);
        }
        return packed;
    }
}
