/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedConvergenceTest {

    @Test
    void reachedCountsAndReturnsDeclaredAction() {
        ForcedConvergence convergence = new ForcedConvergence();
        ForcedConvergence.Verdict verdict = convergence.noteReached("wp-chunk",
            "force materialize", "read-only snapshot");
        assertTrue(verdict.reached());
        assertFalse(verdict.effective());
        assertEquals(1L, convergence.reached());
        assertEquals(1L, convergence.reachedOf("wp-chunk"));
    }

    @Test
    void effectiveOnlyMovesWhenActionRuns() {
        ForcedConvergence convergence = new ForcedConvergence();
        convergence.noteReached("wp-chunk", "force materialize", "snapshot");
        assertEquals(0L, convergence.effective());
        convergence.noteEffective();
        assertEquals(1L, convergence.effective());
    }

    @Test
    void rollbackNeedsCleanWindowAndResetClearsAll() {
        ForcedConvergence convergence = new ForcedConvergence();
        for (int i = 0; i < ForcedConvergence.ROLLBACK_WINDOW_TICKS; i++) {
            convergence.noteTick(false);
        }
        assertTrue(convergence.rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS).ready());
        convergence.noteTick(true);
        assertFalse(convergence.rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS).ready());
        convergence.reset();
        assertEquals(0L, convergence.reached());
        assertEquals(0L, convergence.effective());
    }
}
