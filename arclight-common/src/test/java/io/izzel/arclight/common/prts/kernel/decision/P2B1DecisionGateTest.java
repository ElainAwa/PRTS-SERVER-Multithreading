/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The B1 gate alternates execute/skip per entity, never two skips in a row, and only the first
 * real skip carries the armed token; cleanup/running are outside this gate. */
class P2B1DecisionGateTest {

    private static final String WORLD = "minecraft:overworld";
    private static final UUID MOB = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        B1DecisionGate.reset();
    }

    @Test
    void eligibilityRejectsBrainDrivenAndGoalLessMobs() {
        assertTrue(B1DecisionGate.eligible(false, true));
        assertFalse(B1DecisionGate.eligible(true, true));
        assertFalse(B1DecisionGate.eligible(false, false));
        assertFalse(B1DecisionGate.eligible(true, false));
    }

    @Test
    void unarmedAndIneligibleDoNothing() {
        assertFalse(B1DecisionGate.decide(WORLD, MOB, true).suppress());
        B1DecisionGate.arm(WORLD, 7L, 1L);
        assertFalse(B1DecisionGate.decide(WORLD, MOB, false).eligible());
        assertFalse(B1DecisionGate.decide(WORLD, MOB, false).suppress());
    }

    @Test
    void alternatesAndOnlyFirstSkipCarriesToken() {
        B1DecisionGate.arm(WORLD, 7L, 1L);
        B1DecisionGate.Decision first = B1DecisionGate.decide(WORLD, MOB, true);
        assertFalse(first.suppress());
        B1DecisionGate.Decision second = B1DecisionGate.decide(WORLD, MOB, true);
        assertTrue(second.suppress());
        assertTrue(second.firstSkip());
        assertEquals(7L, second.token());
        B1DecisionGate.Decision third = B1DecisionGate.decide(WORLD, MOB, true);
        assertFalse(third.suppress());
        B1DecisionGate.Decision fourth = B1DecisionGate.decide(WORLD, MOB, true);
        assertTrue(fourth.suppress());
        assertFalse(fourth.firstSkip());
        assertEquals(0L, fourth.token());
        assertEquals(4L, B1DecisionGate.eligibleCount());
        assertEquals(2L, B1DecisionGate.skippedCount());
        assertEquals(2L, B1DecisionGate.forwardedCount());
    }

    @Test
    void disarmAndNewGenerationResetPattern() {
        B1DecisionGate.arm(WORLD, 1L, 1L);
        assertEquals(false, B1DecisionGate.decide(WORLD, MOB, true).suppress());
        B1DecisionGate.disarm(WORLD);
        assertFalse(B1DecisionGate.decide(WORLD, MOB, true).suppress());
        B1DecisionGate.arm(WORLD, 2L, 2L);
        assertFalse(B1DecisionGate.decide(WORLD, MOB, true).suppress());
        assertTrue(B1DecisionGate.decide(WORLD, MOB, true).suppress());
    }

    @Test
    void entitiesAndWorldsAreIsolated() {
        UUID other = UUID.randomUUID();
        B1DecisionGate.arm(WORLD, 1L, 1L);
        B1DecisionGate.arm("minecraft:the_nether", 2L, 1L);
        assertFalse(B1DecisionGate.decide(WORLD, MOB, true).suppress());
        assertTrue(B1DecisionGate.decide(WORLD, MOB, true).suppress());
        assertFalse(B1DecisionGate.decide(WORLD, other, true).suppress());
        assertFalse(B1DecisionGate.decide("minecraft:the_nether", MOB, true).suppress());
    }

    @Test
    void staleArmIsClearedWithoutApply() {
        B1DecisionGate.arm(WORLD, 5L, 10L);
        assertTrue(B1DecisionGate.decide(WORLD, MOB, true).eligible());
        B1DecisionGate.clearStale(100L, 50L);
        assertFalse(B1DecisionGate.decide(WORLD, MOB, true).suppress());
    }

    @Test
    void suppressionFlagIsThreadLocalAndCleared() {
        assertFalse(B1DecisionGate.suppressed());
        B1DecisionGate.beginSuppress();
        assertTrue(B1DecisionGate.suppressed());
        B1DecisionGate.endSuppress();
        assertFalse(B1DecisionGate.suppressed());
    }
}
