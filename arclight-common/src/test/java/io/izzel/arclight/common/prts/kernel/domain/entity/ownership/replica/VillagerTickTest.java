/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole-tick model of a villager row: the guard that refuses a row the model does not cover,
 * the shared mob path, and the two tails the villager class adds to it. */
class VillagerTickTest {

    @Test
    void theModelRefusesARowItDoesNotCover() {
        TickState state = replicable();
        state.firstTick = true;
        assertFalse(VillagerTick.MODEL.compute(state), "a first tick was answered for");
        state = replicable();
        state.jumping = true;
        assertFalse(VillagerTick.MODEL.compute(state), "a jumping row was answered for");
        state = replicable();
        state.effectsDirty = true;
        assertFalse(VillagerTick.MODEL.compute(state), "a dirty effect set was answered for");
        state = replicable();
        state.appliedScale = 0.5F;
        assertFalse(VillagerTick.MODEL.compute(state), "a row of another scale was answered for");
        assertTrue(VillagerTick.MODEL.compute(replicable()), "a covered row was refused");
    }

    @Test
    void theTickDampsTheMotionAndZeroesTheSmallComponents() {
        TickState state = replicable();
        state.vx = 0.1;
        state.vy = 0.0;
        state.vz = -0.003;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(0.1 * 0.98, state.vx);
        assertEquals(0.0, state.vy);
        assertEquals(0.0, state.vz, "a component below the cut was kept");
    }

    @Test
    void theTickWalksTheAgeBackToZero() {
        TickState state = replicable();
        state.age = -3;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(-2, state.age, "a baby did not age towards zero");
        state = replicable();
        state.age = 5;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(4, state.age, "an adult did not age towards zero");
        state = replicable();
        state.age = 0;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(0, state.age);
    }

    @Test
    void theTickRunsTheUnhappyCounterDown() {
        TickState state = replicable();
        state.unhappyCounter = 3;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(2, state.unhappyCounter);
        state = replicable();
        state.unhappyCounter = 0;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(0, state.unhappyCounter);
    }

    @Test
    void theTickStepsTheBodyRotationTowardsTheHead() {
        TickState state = replicable();
        state.yRot = 0.0F;
        state.yHeadRot = 170.0F;
        state.yBodyRot = 0.0F;
        state.bodyLastStableYHeadRot = 170.0F;
        state.bodyHeadStableTime = 11;
        state.headTurnLimit = 75;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(12, state.bodyHeadStableTime);
        // The head has been stable for twelve ticks, so the body follows it within the head limit
        // that decays with the stable time: 75 * (1 - 2/10) = 60 degrees of the 170 degree gap.
        assertEquals(110.0F, state.yBodyRot, 1.0E-6F,
            "the body did not turn towards a head that has been stable for twelve ticks");
    }

    @Test
    void theTickWalksTheAnimationState() {
        TickState state = replicable();
        state.x = 1.0;
        state.xo = 0.0;
        state.z = 0.0;
        state.zo = 0.0;
        state.onGround = true;
        state.walkSpeed = 0.5F;
        state.walkPosition = 2.0F;
        assertTrue(VillagerTick.MODEL.compute(state));
        assertEquals(0.5F, state.walkSpeedOld);
        // The animation input is min(4 * distance, 1), folded in with the 0.4 factor of the update.
        assertEquals(0.7F, state.walkSpeed, 1.0E-6F, "the animation input was not folded in");
        assertEquals(2.7F, state.walkPosition, 1.0E-6F);
    }

    private static TickState replicable() {
        TickState state = new TickState();
        state.appliedScale = 1.0F;
        state.headTurnLimit = 75;
        state.onGround = true;
        return state;
    }
}
