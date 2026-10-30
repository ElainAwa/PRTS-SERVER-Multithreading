/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The A-branch return gap: A2/A3 have no real second condition, and the ladder has no return
 * driver behind the gate. A bound condition can be returned only when something calls it. */
class P2F04eWaitReturnGapTest {

    private static final long TICK = 100L;

    @BeforeEach
    void enable() {
        System.setProperty("arclight.prts.kernel", "true");
    }

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void a2AndA3AreUnboundAfterTheCleanWindow() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.noteEntered(WaitLadder.Level.A1, TICK);
        ladder.noteEntered(WaitLadder.Level.A2, TICK);
        ladder.noteEntered(WaitLadder.Level.A3, TICK);
        for (int index = 0; index < 3; index++) {
            ladder.noteTick(false);
        }

        WaitLadder.Gate a2 = ladder.gate(WaitLadder.Level.A2, 3);
        WaitLadder.Gate a3 = ladder.gate(WaitLadder.Level.A3, 3);
        assertFalse(a2.secondBound());
        assertFalse(a3.secondBound());
        assertEquals("second_condition_unbound", a2.blockedBy());
        assertEquals("second_condition_unbound", a3.blockedBy());
        assertFalse(a2.ready());
        assertFalse(a3.ready());
        assertEquals(0L, ladder.returnedTotal());
    }

    @Test
    void theLiveModuleBindsAllThreeARungs() {
        KernelModule module = KernelModule.instance();
        WaitLadder ladder = module.waitLadder();

        assertTrue(ladder.gate(WaitLadder.Level.A1, 3).secondBound());
        assertTrue(ladder.gate(WaitLadder.Level.A2, 3).secondBound());
        assertTrue(ladder.gate(WaitLadder.Level.A3, 3).secondBound());
        assertEquals(0L, ladder.returnedTotal());
    }

    @Test
    void aBoundConditionReturnsOnlyWhenTheGateIsAskedAndReturnIsCalled() {
        WaitLadder ladder = new WaitLadder(() -> true);
        ladder.bindSecondCondition(WaitLadder.Level.A1, "test.progress", () -> true);
        ladder.noteEntered(WaitLadder.Level.A1, TICK);
        for (int index = 0; index < 3; index++) {
            ladder.noteTick(false);
        }

        assertTrue(ladder.gate(WaitLadder.Level.A1, 3).ready());
        assertEquals(0L, ladder.returnedTotal());
        assertTrue(ladder.noteReturned(WaitLadder.Level.A1));
        assertEquals(1L, ladder.returnedTotal());
        assertEquals(WaitLadder.Level.NONE, ladder.sign().deepest());
    }
}
