/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ACC-13 alloc observation is the outstanding-allocation gauge, not the cumulative claim
 * counter: a steady claim/release load keeps the gauge flat (slope zero), while the cumulative
 * counter that the old wiring fed is positive by construction. The arena's cumulative counters keep
 * their own meaning. */
class P2ACC13AllocMetricTest {

    private static final long PLAN_EPOCH = 1L;

    @Test
    void theOldCumulativeFeedIsPositiveUnderSteadyLoad() {
        HealthReadings.reset();
        HealthReadings.noteTick(0L, 0L, 0L, 0L, 1L, 2L, 0L);
        HealthReadings.noteTick(1L, 0L, 0L, 0L, 2L, 2L, 0L);
        HealthReadings.noteTick(2L, 0L, 0L, 0L, 3L, 2L, 0L);
        assertEquals(1.0, HealthReadings.allocSlope(), 1.0e-9,
            "the old cumulative claims feed is positive by definition under steady load");
        HealthReadings.reset();
    }

    @Test
    void theOutstandingGaugeIsFlatUnderSteadyClaimAndReleasePairs() {
        ArenaLedger ledger = new ArenaLedger();
        HealthReadings.reset();
        for (long tick = 0L; tick <= 2L; tick++) {
            ArenaSlot slot = ledger.claim(tick + 1L, "world", "r0.0", 7, 4);
            ledger.release(slot.lease(PLAN_EPOCH), true);
            HealthReadings.noteTick(tick, 0L, 0L, 0L, ledger.activeClaims(), 2L, 0L);
        }
        assertEquals(0L, ledger.activeClaims());
        assertEquals(3L, ledger.claims(), "the cumulative claims counter keeps its meaning");
        assertEquals(3L, ledger.releases(), "the cumulative releases counter keeps its meaning");
        assertEquals(0.0, HealthReadings.allocSlope(), 1.0e-9,
            "a steady load has a flat outstanding gauge, so the alloc slope is zero");
        HealthReadings.reset();
    }

    @Test
    void activeClaimsIsClaimsMinusReleasesAndNeverNegative() {
        ArenaLedger ledger = new ArenaLedger();
        assertEquals(0L, ledger.activeClaims());
        for (long batch = 1L; batch <= 3L; batch++) {
            ledger.claim(batch, "world", "r0.0", 7, 4);
        }
        assertEquals(3L, ledger.activeClaims());
        ledger.releaseAll();
        assertEquals(0L, ledger.activeClaims());
        assertEquals(3L, ledger.claims());
        assertEquals(3L, ledger.releases());
        ledger.reset();
        assertEquals(0L, ledger.activeClaims());
    }

    @Test
    void aGrowingOutstandingBacklogIsStillVisible() {
        ArenaLedger ledger = new ArenaLedger();
        HealthReadings.reset();
        for (long tick = 0L; tick <= 2L; tick++) {
            ledger.claim(tick + 1L, "world", "r0.0", 7, 4);
            HealthReadings.noteTick(tick, 0L, 0L, 0L, ledger.activeClaims(), 2L, 0L);
        }
        assertEquals(1.0, HealthReadings.allocSlope(), 1.0e-9,
            "a real backlog growth is still visible in the gauge");
        HealthReadings.reset();
    }
}
