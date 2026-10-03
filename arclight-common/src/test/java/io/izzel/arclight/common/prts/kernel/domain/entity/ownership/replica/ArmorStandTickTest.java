/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole-tick model: the state transitions a no-physics armour stand tick performs, the guard
 * that refuses a row the model does not cover, and the copy the worker answers on. */
class ArmorStandTickTest {

    @Test
    void theModelRefusesARowItDoesNotCover() {
        TickState state = replicable();
        state.firstTick = true;
        assertFalse(ArmorStandTick.compute(state), "a first tick was answered for");
        state = replicable();
        state.effectsDirty = true;
        assertFalse(ArmorStandTick.compute(state), "a dirty effect set was answered for");
        state = replicable();
        state.ticksFrozen = 1;
        assertFalse(ArmorStandTick.compute(state), "a frozen row was answered for");
        state = replicable();
        state.appliedScale = 0.5F;
        assertFalse(ArmorStandTick.compute(state), "a row of another scale was answered for");
        assertTrue(ArmorStandTick.compute(replicable()), "a covered row was refused");
    }

    @Test
    void theTickDampsTheMotionAndZeroesTheSmallComponents() {
        TickState state = replicable();
        state.vx = 0.1;
        state.vy = 0.0;
        state.vz = -0.003;
        assertTrue(ArmorStandTick.compute(state));
        assertEquals(0.1 * 0.98, state.vx);
        assertEquals(0.0, state.vy);
        assertEquals(0.0, state.vz, "a component below the cut was kept");
    }

    @Test
    void theTickCopiesTheRotationLags() {
        TickState state = replicable();
        state.yRot = 30.0F;
        state.xRot = 15.0F;
        state.yRotO = -999.0F;
        state.xRotO = -999.0F;
        state.yBodyRot = -999.0F;
        state.yBodyRotO = -999.0F;
        state.yHeadRot = 15.0F;
        state.yHeadRotO = -999.0F;
        assertTrue(ArmorStandTick.compute(state));
        assertEquals(30.0F, state.yRotO);
        assertEquals(15.0F, state.xRotO);
        // The armour stand head turn copies the yaw lag onto the body and the yaw onto the body.
        assertEquals(30.0F, state.yRotO);
        assertEquals(30.0F, state.yBodyRot);
        assertEquals(30.0F, state.yBodyRotO);
        assertEquals(15.0F, state.yHeadRotO);
    }

    @Test
    void theTickRunsItsWalkBookkeeping() {
        TickState state = replicable();
        state.x = 1.0;
        state.xo = 0.0;
        state.z = 0.0;
        state.zo = 0.0;
        state.onGround = true;
        state.run = 0.5F;
        assertTrue(ArmorStandTick.compute(state));
        assertEquals(0.65F, state.run, 1.0E-6F, "a grounded row did not walk");
        assertEquals(0.5F, state.oRun);
        state = replicable();
        state.onGround = true;
        state.run = 0.5F;
        assertTrue(ArmorStandTick.compute(state));
        assertEquals(0.35F, state.run, 1.0E-6F, "a row that did not move kept walking");
    }

    @Test
    void theWorkerAnswersOnACopyOfTheCapturedRow() {
        TickState captured = replicable();
        captured.entityId = 42;
        captured.vx = 0.5;
        TickState answer = new TickState();
        answer.copyFrom(captured);
        for (Field field : TickState.class.getFields()) {
            try {
                assertEquals(field.get(captured), field.get(answer), field.getName() + " was not copied");
            } catch (IllegalAccessException refused) {
                throw new AssertionError(refused);
            }
        }
        assertTrue(ArmorStandTick.compute(answer));
        assertNotEquals(captured.vx, answer.vx, "the captured row was answered on in place");
        assertEquals(0.5, captured.vx, "the captured row changed under the worker");
    }

    private static TickState replicable() {
        TickState state = new TickState();
        state.appliedScale = 1.0F;
        return state;
    }
}
