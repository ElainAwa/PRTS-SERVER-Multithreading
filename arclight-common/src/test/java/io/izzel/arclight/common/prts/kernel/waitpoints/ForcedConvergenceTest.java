/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bound action seam and the rollback gate: reached is not effective, and the gate only opens
 * after a whole window of clean ticks. */
class ForcedConvergenceTest {

    @Test
    void aCrossedWaitIsReachedAndNothingIsReportedAsExecuted() {
        ForcedConvergence gate = new ForcedConvergence();
        ForcedConvergence.Verdict verdict = gate.noteReached("chunk", "forced materialization",
            "read-only snapshot");

        assertTrue(verdict.reached());
        assertFalse(verdict.effective());
        assertEquals("WAIT_BOUND_EXCEEDED", verdict.code());
        assertEquals("chunk", verdict.wpId());
        assertEquals(1L, gate.reached());
        assertEquals(1L, gate.reachedOf("chunk"));
        assertEquals(0L, gate.reachedOf("save"));
        assertEquals(0L, gate.effective());
    }

    @Test
    void theActionSeamCountsAnActionOnlyWhenItIsCalled() {
        ForcedConvergence gate = new ForcedConvergence();

        gate.noteEffective();

        assertEquals(1L, gate.effective());
    }

    @Test
    void theRollbackGateNeedsAWholeWindowOfCleanTicks() {
        ForcedConvergence gate = new ForcedConvergence();
        gate.noteTick(true);
        gate.noteTick(false);
        ForcedConvergence.Rollback early = gate.rollback(3);

        gate.noteTick(false);
        gate.noteTick(false);
        ForcedConvergence.Rollback ready = gate.rollback(3);

        assertFalse(early.ready());
        assertTrue(ready.ready());
        assertEquals(3, ready.windowTicks());
        assertEquals(4, ready.ticksObserved());
        assertEquals(3, ready.ticksClean());

        gate.noteTick(true);

        assertFalse(gate.rollback(3).ready());
    }
}
