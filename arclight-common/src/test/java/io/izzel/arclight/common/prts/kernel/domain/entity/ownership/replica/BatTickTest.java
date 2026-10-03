/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole-tick model of a bat row: the shared mob path plus the two branches of Bat#tick, the
 * resting snap and the damped vertical motion of a flying row. */
class BatTickTest {

    @Test
    void theModelRefusesARowItDoesNotCover() {
        TickState state = replicable();
        state.firstTick = true;
        assertFalse(BatTick.MODEL.compute(state), "a first tick was answered for");
        state = replicable();
        state.lerpSteps = 1;
        assertFalse(BatTick.MODEL.compute(state), "a row with a pending lerp was answered for");
        state = replicable();
        state.appliedScale = 0.5F;
        assertFalse(BatTick.MODEL.compute(state), "a row of another scale was answered for");
        assertTrue(BatTick.MODEL.compute(replicable()), "a covered row was refused");
    }

    @Test
    void aRestingBatStopsAndSnapsOntoTheBlockBelow() {
        TickState state = replicable();
        state.resting = true;
        state.y = 64.7;
        state.bbHeight = 0.9F;
        state.vx = 0.1;
        state.vy = 0.2;
        state.vz = -0.3;
        assertTrue(BatTick.MODEL.compute(state));
        assertEquals(0.0, state.vx);
        assertEquals(0.0, state.vy);
        assertEquals(0.0, state.vz, "a resting bat kept its motion");
        assertEquals(64.0 + 1.0 - (double) 0.9F, state.y, 1.0E-9,
            "the rest snap did not land on the block");
        assertTrue(state.positionSnapped, "the commit was not told to repeat the position write");
    }

    @Test
    void aFlyingBatKeepsADampedVerticalMotion() {
        TickState state = replicable();
        state.resting = false;
        state.vy = 0.5;
        assertTrue(BatTick.MODEL.compute(state));
        assertEquals(0.5 * 0.98 * 0.6, state.vy, 1.0E-9);
        assertFalse(state.positionSnapped, "a flying bat was snapped onto a block");
    }

    @Test
    void theTickDampsTheHorizontalMotionLikeAnyMob() {
        TickState state = replicable();
        state.vx = 0.1;
        assertTrue(BatTick.MODEL.compute(state));
        assertEquals(0.1 * 0.98, state.vx, 1.0E-9);
    }

    private static TickState replicable() {
        TickState state = new TickState();
        state.appliedScale = 1.0F;
        state.headTurnLimit = 75;
        state.onGround = true;
        return state;
    }
}
