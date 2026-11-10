/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitObservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEffectiveTest {

    @Test
    void onlyARealActionMovesEffective() {
        WaitPointRegistry none = new WaitPointRegistry(() -> 50);
        WaitObservation noneObs = none.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 1L, 80L, null), () -> false);
        assertTrue(noneObs.wouldConverge());
        assertEquals(0L, none.convergence().effective());

        WaitPointRegistry real = new WaitPointRegistry(() -> 50);
        WaitObservation realObs = real.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 1L, 80L, null), () -> true);
        assertTrue(realObs.wouldConverge());
        assertEquals(1L, real.convergence().effective());
    }
}
