/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole-tick model of a marker row: the empty tick is the identity on any frozen state, and
 * the model answers for every row of the class. */
class MarkerTickTest {

    @Test
    void theEmptyTickIsAnsweredForEveryState() {
        TickState state = new TickState();
        assertTrue(MarkerTick.MODEL.compute(state), "an empty state was refused");
        state.waiting = true;
        state.radius = 7.0F;
        state.vx = 1.5;
        state.firstTick = true;
        state.inPortal = true;
        assertTrue(MarkerTick.MODEL.compute(state), "a busy state was refused");
    }

    @Test
    void theEmptyTickChangesNoField() {
        TickState state = new TickState();
        state.x = 1.0;
        state.y = 2.0;
        state.z = 3.0;
        state.vx = 0.5;
        state.yRot = 90.0F;
        state.tickCount = 41;
        assertTrue(MarkerTick.MODEL.compute(state));
        assertEquals(1.0, state.x);
        assertEquals(2.0, state.y);
        assertEquals(3.0, state.z);
        assertEquals(0.5, state.vx);
        assertEquals(90.0F, state.yRot);
        assertEquals(41, state.tickCount);
    }

    @Test
    void theClassRefusesNoRowAndNamesItsReason() {
        assertEquals(WholeTickModel.REPLICABLE, MarkerTick.MODEL.refusal(null));
        assertEquals("replicable", MarkerTick.MODEL.refusalNames()[0]);
    }
}
