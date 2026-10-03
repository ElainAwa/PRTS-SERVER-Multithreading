/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole-tick model of an effect-free area effect cloud: the wait flag around the wait time,
 * the radius step and the two branches in which the vanilla tick discards the row. */
class AreaEffectCloudTickTest {

    @Test
    void aWaitingCloudReturnsBeforeTheRadiusStep() {
        TickState state = replicable();
        state.tickCount = 5;
        state.waitTime = 100;
        state.radius = 3.0F;
        state.radiusPerTick = 0.5F;
        state.waiting = true;
        assertTrue(AreaEffectCloudTick.MODEL.compute(state));
        assertEquals(3.0F, state.radius, "a waiting cloud stepped its radius");
        assertFalse(state.radiusStepped);
        assertFalse(state.waitingChanged);
    }

    @Test
    void theWaitFlagTurnsOverWhenTheWaitTimeElapses() {
        TickState state = replicable();
        state.tickCount = 100;
        state.waitTime = 100;
        state.waiting = true;
        assertTrue(AreaEffectCloudTick.MODEL.compute(state));
        assertFalse(state.waiting, "the cloud kept waiting past its wait time");
        assertTrue(state.waitingChanged, "the wait flag write was not announced");
    }

    @Test
    void anActiveCloudStepsItsRadius() {
        TickState state = replicable();
        state.tickCount = 10;
        state.radius = 1.0F;
        state.radiusPerTick = 0.01F;
        assertTrue(AreaEffectCloudTick.MODEL.compute(state));
        assertEquals(1.01F, state.radius, 1.0E-6F);
        assertTrue(state.radiusStepped);
    }

    @Test
    void theRadiusStepIsClampedLikeTheDataWrite() {
        TickState state = replicable();
        state.tickCount = 10;
        state.radius = 32.0F;
        state.radiusPerTick = 1.0F;
        assertTrue(AreaEffectCloudTick.MODEL.compute(state));
        assertEquals(32.0F, state.radius, "the radius passed the clamp of the data write");
        assertTrue(state.radiusStepped);
    }

    @Test
    void theModelRefusesTheRowTheTickWouldDiscard() {
        TickState state = replicable();
        state.tickCount = 700;
        state.waitTime = 100;
        state.duration = 600;
        assertFalse(AreaEffectCloudTick.MODEL.compute(state), "an expiring cloud was answered for");
        state = replicable();
        state.tickCount = 10;
        state.radius = 0.5F;
        state.radiusPerTick = -0.01F;
        assertFalse(AreaEffectCloudTick.MODEL.compute(state), "a cloud at the radius floor was answered for");
        state = replicable();
        state.firstTick = true;
        assertFalse(AreaEffectCloudTick.MODEL.compute(state), "a first tick was answered for");
        state = replicable();
        state.inPortal = true;
        assertFalse(AreaEffectCloudTick.MODEL.compute(state), "a portal row was answered for");
    }

    @Test
    void theBaseTickWritesTheLags() {
        TickState state = replicable();
        state.walkDist = 4.0F;
        state.walkDistO = 0.0F;
        state.yRot = 30.0F;
        state.yRotO = 10.0F;
        state.xRot = 15.0F;
        state.xRotO = 5.0F;
        state.isInPowderSnow = false;
        state.wasInPowderSnow = true;
        assertTrue(AreaEffectCloudTick.MODEL.compute(state));
        assertEquals(4.0F, state.walkDistO);
        assertEquals(30.0F, state.yRotO);
        assertEquals(15.0F, state.xRotO);
        assertFalse(state.wasInPowderSnow);
        assertFalse(state.isInPowderSnow);
    }

    @Test
    void theModelNamesItsRefusals() {
        String[] names = AreaEffectCloudTick.MODEL.refusalNames();
        assertEquals(AreaEffectCloudTick.REFUSALS, names.length);
        assertEquals("replicable", names[AreaEffectCloudTick.REPLICABLE]);
        assertEquals("effects", names[AreaEffectCloudTick.EFFECTS]);
    }

    private static TickState replicable() {
        TickState state = new TickState();
        state.waitTime = 0;
        state.duration = 600;
        state.tickCount = 1;
        return state;
    }
}
