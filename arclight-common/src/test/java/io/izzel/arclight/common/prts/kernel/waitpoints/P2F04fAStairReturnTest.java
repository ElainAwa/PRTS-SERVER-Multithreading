/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The A branch now has a per-tick wait source, three bound conditions and a return driver; these
 * tests pin the ladder contract and the state the driver reads each tick. */
class P2F04fAStairReturnTest {

    private static final long TICK = 100L;

    @Test
    void allThreeARungsReturnWhenTheirConditionsAreReady() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A1, "test.progress", () -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A2, "test.wait_ms", () -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A3, "test.queue_depth", () -> true);
        ladder.noteEntered(WaitLadder.Level.A1, TICK);
        ladder.noteEntered(WaitLadder.Level.A2, TICK);
        ladder.noteEntered(WaitLadder.Level.A3, TICK);
        ladder.noteEffective(WaitLadder.Level.A1);
        ladder.noteEffective(WaitLadder.Level.A2);
        ladder.noteEffective(WaitLadder.Level.A3);
        for (int index = 0; index < 3; index++) {
            ladder.noteTick(false);
        }

        assertTrue(ladder.gate(WaitLadder.Level.A3, 3).ready());
        assertTrue(ladder.gate(WaitLadder.Level.A2, 3).ready());
        assertTrue(ladder.gate(WaitLadder.Level.A1, 3).ready());
        assertTrue(ladder.noteReturned(WaitLadder.Level.A3));
        assertTrue(ladder.noteReturned(WaitLadder.Level.A2));
        assertTrue(ladder.noteReturned(WaitLadder.Level.A1));
        assertEquals(1L, ladder.counters(WaitLadder.Level.A1).effective());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A2).effective());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A3).effective());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A1).returned());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A2).returned());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A3).returned());
        assertEquals(3L, ladder.returnedTotal());
        assertEquals(WaitLadder.Level.NONE, ladder.sign().deepest());
    }

    @Test
    void theGateStillBlocksOnTheCleanWindowAndOnASecondCondition() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A2, "test.wait_ms", () -> false);
        ladder.noteEntered(WaitLadder.Level.A2, TICK);

        assertEquals("clean_ticks", ladder.gate(WaitLadder.Level.A2, 3).blockedBy());
        for (int index = 0; index < 3; index++) {
            ladder.noteTick(false);
        }
        assertEquals("second_condition", ladder.gate(WaitLadder.Level.A2, 3).blockedBy());
        assertFalse(ladder.gate(WaitLadder.Level.A2, 3).ready());
        assertEquals(0L, ladder.counters(WaitLadder.Level.A2).returned());
    }

    @Test
    void thePerTickWaitSourceClosesWithTheTickAndResets() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 1L, 80L, "7"));
        registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 2L, 20L, "8"));
        registry.noteTick();
        assertEquals(80L, registry.lastTickMaxWaitMs());
        registry.noteTick();
        assertEquals(0L, registry.lastTickMaxWaitMs());

        registry.observeWait("chunk",
            new WaitSpan("chunk", "chunk.materialize", "site:a", "world", 3L, 10L, "9"));
        registry.noteTick();
        assertEquals(10L, registry.lastTickMaxWaitMs());
        registry.resetReadings();
        assertEquals(0L, registry.lastTickMaxWaitMs());
    }
}
