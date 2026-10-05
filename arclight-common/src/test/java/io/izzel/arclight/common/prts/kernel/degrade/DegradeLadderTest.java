/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The five rungs, the order they may not skip, the action flag and the return gate. */
class DegradeLadderTest {

    @Test
    void everyRungCarriesItsFourItemsAndItsCode() {
        DegradeLadder ladder = new DegradeLadder(() -> false);

        assertEquals(5, ladder.rungs().size());
        for (DegradeLadder.Rung rung : ladder.rungs()) {
            assertNotNull(rung.level());
            assertNotNull(rung.code());
            assertFalse(rung.trigger().isBlank());
            assertFalse(rung.action().isBlank());
            assertFalse(rung.signal().isBlank());
            assertFalse(rung.returnCondition().isBlank());
        }
        assertEquals(RejectCode.QUOTA_EXCEEDED, ladder.rung(DegradeLevel.B1).code());
        assertEquals(RejectCode.TICK_BUDGET_EXHAUSTED, ladder.rung(DegradeLevel.B5).code());
    }

    @Test
    void theOrderIsWalkedOneRungAtATimeAndASkipRaisesTheExhaustedCode() {
        DegradeLadder ladder = new DegradeLadder(() -> false);
        DegradeLadder.Advance advance = ladder.noteEntered(DegradeLevel.B3, 10L);

        assertTrue(advance.skipped());
        assertEquals(3, advance.entered().size());
        assertEquals(DegradeLevel.B3, advance.reached());
        assertEquals(1L, ladder.counters(DegradeLevel.B1).entered());
        assertEquals(1L, ladder.counters(DegradeLevel.B2).entered());
        assertEquals(1L, ladder.counters(DegradeLevel.B3).entered());
        assertEquals(0L, ladder.counters(DegradeLevel.B4).entered());
        assertEquals(1L, ladder.sign().skippedCount());
        assertEquals(1L, ladder.codeCount(RejectCode.TICK_BUDGET_EXHAUSTED));

        DegradeLadder stepwise = new DegradeLadder(() -> false);
        assertFalse(stepwise.noteEntered(DegradeLevel.B1, 10L).skipped());
        assertFalse(stepwise.noteEntered(DegradeLevel.B2, 11L).skipped());
        assertEquals(0L, stepwise.sign().skippedCount());
    }

    @Test
    void aReachedRungDoesNotActWhileTheFlagIsOff() {
        DegradeLadder ladder = new DegradeLadder(() -> false);
        ladder.noteEntered(DegradeLevel.B1, 10L);

        assertFalse(ladder.noteEffective(DegradeLevel.B1));
        assertFalse(ladder.noteReturned(DegradeLevel.B1));
        assertEquals(0L, ladder.effectiveTotal());
        assertEquals(0L, ladder.returnedTotal());
        assertEquals(DegradeLevel.B1, ladder.sign().deepest());

        DegradeLadder acting = new DegradeLadder(() -> true);
        acting.noteEntered(DegradeLevel.B1, 10L);
        assertTrue(acting.noteEffective(DegradeLevel.B1));
        assertTrue(acting.noteReturned(DegradeLevel.B1));
        assertEquals(1L, acting.effectiveTotal());
        assertEquals(1L, acting.returnedTotal());
        assertEquals(DegradeLevel.NONE, acting.sign().deepest());
    }

    @Test
    void theReturnGateNeedsTheWindowAndTheSecondCondition() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B2, 10L);

        ladder.noteTick(false);
        ladder.noteTick(false);
        DegradeLadder.Gate early = ladder.gate(DegradeLevel.B2, 3);
        assertFalse(early.ready());
        assertEquals("clean_ticks", early.blockedBy());

        ladder.noteTick(false);
        DegradeLadder.Gate unbound = ladder.gate(DegradeLevel.B2, 3);
        assertFalse(unbound.secondBound());
        assertEquals("unbound", unbound.secondSource());
        assertEquals("second_condition_unbound", unbound.blockedBy());

        ladder.bindSecondCondition(DegradeLevel.B2, "fixture.queue_depth", () -> false);
        assertEquals("second_condition", ladder.gate(DegradeLevel.B2, 3).blockedBy());

        ladder.bindSecondCondition(DegradeLevel.B2, "fixture.queue_depth", () -> true);
        DegradeLadder.Gate ready = ladder.gate(DegradeLevel.B2, 3);
        assertTrue(ready.ready());
        assertEquals("none", ready.blockedBy());

        ladder.noteTick(true);
        assertFalse(ladder.gate(DegradeLevel.B2, 3).ready());
        assertEquals(0L, ladder.sign().cleanTicks());
        assertEquals(4L, ladder.sign().ticksObserved());
    }

    @Test
    void theDeclaredWindowIsTheDefaultTheGateIsAskedFor() {
        assertEquals(3, DegradeLadder.DECLARED_RETURN_TICKS);
    }
}
