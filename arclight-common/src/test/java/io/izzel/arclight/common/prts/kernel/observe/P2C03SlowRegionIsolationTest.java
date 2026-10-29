/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slow-region isolation: an over-share region defers only its own writes, never a global
 * barrier, and returns only after the declared run of clean ticks with a falling backlog. */
class P2C03SlowRegionIsolationTest {

    @Test
    void aSlowRegionDefersOnlyItsOwnWritesAndRaisesNoGlobalBarrier() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true,
            RegionIdentityObserver.ROLLBACK_GATE_TICKS);

        SlowRegionIsolation.Decision slow = isolation.observe("p2c03-slow",
            new SlowRegionIsolation.Region(5.0, 9.0, 100L));
        SlowRegionIsolation.Decision fast = isolation.observe("p2c03-fast",
            new SlowRegionIsolation.Region(5.0, 1.0, 10L));

        assertTrue(slow.isolated());
        assertTrue(slow.writeDeferred());
        assertTrue(slow.overShare());
        assertFalse(fast.isolated());
        assertFalse(fast.writeDeferred());
        assertEquals(0L, slow.barrierWaits());
        assertEquals(0L, fast.barrierWaits());
        assertEquals(0L, isolation.barrierWaitCount());
        assertEquals(0L, new RegionIdentityObserver().skip());
        assertEquals(0L, new RegionIdentityObserver().barrierWaitCount());
        assertEquals(1L, isolation.skipped());
    }

    @Test
    void theRollbackGateNeedsTheWholeCleanRunAndAFallingBacklog() {
        int window = RegionIdentityObserver.ROLLBACK_GATE_TICKS;
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, window);
        isolation.observe("p2c03-rollback", new SlowRegionIsolation.Region(5.0, 9.0, 100L));

        for (int tick = 1; tick < window; tick++) {
            SlowRegionIsolation.Decision decision = isolation.observe("p2c03-rollback",
                new SlowRegionIsolation.Region(5.0, 1.0, 100L - tick));
            assertTrue(decision.isolated(), "the gate is not met before the window");
            assertFalse(decision.rolledBack());
        }
        SlowRegionIsolation.Decision atWindow = isolation.observe("p2c03-rollback",
            new SlowRegionIsolation.Region(5.0, 1.0, 100L - window));
        assertTrue(atWindow.rolledBack());
        assertFalse(atWindow.isolated());
        assertFalse(atWindow.writeDeferred());
        assertEquals(1L, isolation.rollbacks());
    }

    @Test
    void aRisingBacklogKeepsTheRegionIsolatedEvenAfterCleanTicks() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, 3);
        isolation.observe("p2c03-backlog", new SlowRegionIsolation.Region(5.0, 9.0, 100L));

        for (int tick = 1; tick <= 8; tick++) {
            SlowRegionIsolation.Decision decision = isolation.observe("p2c03-backlog",
                new SlowRegionIsolation.Region(5.0, 1.0, 100L + tick * 10L));
            assertTrue(decision.isolated());
            assertFalse(decision.backlogFalling());
            assertFalse(decision.rolledBack());
        }
        assertEquals(0L, isolation.rollbacks());
    }

    @Test
    void aMidRunRisingBacklogResetsTheCleanStreak() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, 3);
        isolation.observe("p2c03-mixed", new SlowRegionIsolation.Region(5.0, 9.0, 100L));

        SlowRegionIsolation.Decision rising1 = isolation.observe("p2c03-mixed",
            new SlowRegionIsolation.Region(5.0, 1.0, 110L));
        SlowRegionIsolation.Decision rising2 = isolation.observe("p2c03-mixed",
            new SlowRegionIsolation.Region(5.0, 1.0, 120L));
        SlowRegionIsolation.Decision falling = isolation.observe("p2c03-mixed",
            new SlowRegionIsolation.Region(5.0, 1.0, 90L));
        assertEquals(0L, rising1.cleanTicks());
        assertEquals(0L, rising2.cleanTicks());
        assertEquals(1L, falling.cleanTicks(),
            "a falling backlog after a rise starts the run over");
        assertFalse(falling.rolledBack(), "the old implementation rolled back here");

        isolation.observe("p2c03-mixed", new SlowRegionIsolation.Region(5.0, 1.0, 80L));
        SlowRegionIsolation.Decision atWindow = isolation.observe("p2c03-mixed",
            new SlowRegionIsolation.Region(5.0, 1.0, 70L));
        assertTrue(atWindow.rolledBack());
        assertEquals(1L, isolation.rollbacks());
    }

    @Test
    void aLastRisingBacklogBlocksTheRollback() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, 3);
        isolation.observe("p2c03-last-rise", new SlowRegionIsolation.Region(5.0, 9.0, 100L));

        isolation.observe("p2c03-last-rise", new SlowRegionIsolation.Region(5.0, 1.0, 90L));
        isolation.observe("p2c03-last-rise", new SlowRegionIsolation.Region(5.0, 1.0, 80L));
        SlowRegionIsolation.Decision rising = isolation.observe("p2c03-last-rise",
            new SlowRegionIsolation.Region(5.0, 1.0, 100L));
        assertEquals(0L, rising.cleanTicks());
        assertFalse(rising.rolledBack());

        SlowRegionIsolation.Decision afterRise = isolation.observe("p2c03-last-rise",
            new SlowRegionIsolation.Region(5.0, 1.0, 90L));
        assertFalse(afterRise.rolledBack(), "the last rising tick reset the run");
        assertEquals(1L, afterRise.cleanTicks());
        isolation.observe("p2c03-last-rise", new SlowRegionIsolation.Region(5.0, 1.0, 80L));
        SlowRegionIsolation.Decision rolledBack = isolation.observe("p2c03-last-rise",
            new SlowRegionIsolation.Region(5.0, 1.0, 70L));
        assertTrue(rolledBack.rolledBack());
    }

    @Test
    void theDefaultIsDisabledAndAnOverShareRegionIsNotIsolated() {
        SlowRegionIsolation isolation = new SlowRegionIsolation();
        SlowRegionIsolation.Decision decision = isolation.observe("p2c03-off",
            new SlowRegionIsolation.Region(5.0, 9.0, 100L));

        assertFalse(decision.isolated());
        assertFalse(decision.writeDeferred());
        assertEquals(0L, isolation.skipped());
        assertEquals(0L, isolation.barrierWaitCount());
    }

    @Test
    void zeroSharesAndEmptyStatesStayNeutral() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, 3);
        SlowRegionIsolation.Decision decision = isolation.observe("p2c03-zero",
            new SlowRegionIsolation.Region(0.0, 0.0, 0L));

        assertFalse(decision.isolated());
        assertFalse(decision.writeDeferred());
        assertFalse(decision.overShare());
        assertEquals(0L, decision.barrierWaits());
        assertEquals(0L, isolation.skipped());
        assertEquals(0L, isolation.rollbacks());
    }

    @Test
    void resetClearsTheRegionStateAndTheCounters() {
        SlowRegionIsolation isolation = new SlowRegionIsolation(true, 3);
        isolation.observe("p2c03-reset", new SlowRegionIsolation.Region(5.0, 9.0, 100L));
        isolation.reset();

        assertFalse(isolation.isolated("p2c03-reset"));
        assertEquals(0L, isolation.skipped());
        assertEquals(0L, isolation.rollbacks());
    }
}
