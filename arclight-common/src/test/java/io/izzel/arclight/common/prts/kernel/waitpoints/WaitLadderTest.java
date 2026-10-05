/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wait ladder walks its rungs one at a time, counts a rung that acted apart from a rung that
 * was reached, and lets a rung return only when both of its gate's conditions hold. */
class WaitLadderTest {

    private static final long TICK = 40L;

    @Test
    void everyRungCarriesItsFourItems() {
        WaitLadder ladder = new WaitLadder(() -> false);

        assertEquals(3, ladder.rungs().size());
        for (WaitLadder.Rung rung : ladder.rungs()) {
            assertFalse(rung.trigger().isBlank(), () -> rung.level() + " has no trigger");
            assertFalse(rung.action().isBlank(), () -> rung.level() + " has no action");
            assertFalse(rung.signal().isBlank(), () -> rung.level() + " has no signal");
            assertFalse(rung.returnCondition().isBlank(), () -> rung.level() + " has no return");
        }
    }

    @Test
    void theOrderIsWalkedAndASingleCallThatJumpsItIsCounted() {
        WaitLadder ladder = new WaitLadder(() -> false);

        WaitLadder.Advance first = ladder.noteEntered(WaitLadder.Level.A1, TICK);
        WaitLadder.Advance second = ladder.noteEntered(WaitLadder.Level.A2, TICK);

        assertEquals(1, first.entered().size());
        assertEquals(1, second.entered().size());
        assertFalse(first.skipped());
        assertFalse(second.skipped());
        assertEquals(WaitLadder.Level.A2, second.reached());
        assertEquals(1L, ladder.counters(WaitLadder.Level.A2).entered());

        WaitLadder jumping = new WaitLadder(() -> false);
        WaitLadder.Advance jumped = jumping.noteEntered(WaitLadder.Level.A3, TICK);

        assertTrue(jumped.skipped());
        assertEquals(3, jumped.entered().size());
        assertEquals(1L, jumping.sign().skippedCount());
    }

    @Test
    void anActionIsOnlyCountedWhileItsSwitchIsOn() {
        WaitLadder quiet = new WaitLadder(() -> false);
        WaitLadder acting = new WaitLadder(() -> true);
        for (WaitLadder ladder : new WaitLadder[] {quiet, acting}) {
            ladder.noteEntered(WaitLadder.Level.A1, TICK);
        }

        assertFalse(quiet.noteEffective(WaitLadder.Level.A1));
        assertTrue(acting.noteEffective(WaitLadder.Level.A1));
        assertEquals(0L, quiet.effectiveTotal());
        assertEquals(1L, acting.effectiveTotal());
    }

    @Test
    void theGateNeedsTheCleanRunAndTheProgressSignal() {
        AtomicBoolean recovered = new AtomicBoolean(false);
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A1, "test.progress", recovered::get);
        ladder.noteEntered(WaitLadder.Level.A1, TICK);

        assertEquals("clean_ticks", ladder.gate(WaitLadder.Level.A1, 3).blockedBy());
        for (int index = 0; index < 3; index++) {
            ladder.noteTick(false);
        }
        assertFalse(ladder.gate(WaitLadder.Level.A1, 3).ready());
        assertEquals("second_condition", ladder.gate(WaitLadder.Level.A1, 3).blockedBy());

        recovered.set(true);
        assertTrue(ladder.gate(WaitLadder.Level.A1, 3).ready());
        assertEquals("none", ladder.gate(WaitLadder.Level.A1, 3).blockedBy());

        ladder.noteTick(true);
        assertEquals("clean_ticks", ladder.gate(WaitLadder.Level.A1, 3).blockedBy());
    }

    @Test
    void anUnboundRungCanNeverReturn() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.noteEntered(WaitLadder.Level.A2, TICK);
        for (int index = 0; index < 5; index++) {
            ladder.noteTick(false);
        }

        WaitLadder.Gate gate = ladder.gate(WaitLadder.Level.A2, 3);

        assertFalse(gate.secondBound());
        assertFalse(gate.ready());
        assertEquals("second_condition_unbound", gate.blockedBy());
    }

    @Test
    void aReturnedRungDropsBackToWhatIsStillInForce() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.noteEntered(WaitLadder.Level.A1, TICK);
        ladder.noteEntered(WaitLadder.Level.A2, TICK);

        assertTrue(ladder.noteReturned(WaitLadder.Level.A2));
        assertEquals(WaitLadder.Level.A1, ladder.sign().deepest());
        assertTrue(ladder.noteReturned(WaitLadder.Level.A1));
        assertEquals(WaitLadder.Level.NONE, ladder.sign().deepest());
    }
}
