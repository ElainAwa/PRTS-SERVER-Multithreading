/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lower target after a higher rung is an answer, not a walk: it must not build a negative
 * capacity, count, skip, or move the entered tick. */
class P2F04cDegradeLowerBoundTest {

    @Test
    void enteringBelowTheDeepestRungIsIgnoredWithoutNegativeCapacity() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B3, 10L);
        long enteredB1 = ladder.counters(DegradeLevel.B1).entered();
        long enteredB2 = ladder.counters(DegradeLevel.B2).entered();
        long enteredB3 = ladder.counters(DegradeLevel.B3).entered();
        long skipped = ladder.sign().skippedCount();

        DegradeLadder.Advance backToB1 = ladder.noteEntered(DegradeLevel.B1, 11L);
        assertEquals(DegradeLevel.B3, backToB1.reached());
        assertTrue(backToB1.entered().isEmpty());
        assertFalse(backToB1.skipped());
        assertEquals(enteredB1, ladder.counters(DegradeLevel.B1).entered());
        assertEquals(enteredB2, ladder.counters(DegradeLevel.B2).entered());
        assertEquals(enteredB3, ladder.counters(DegradeLevel.B3).entered());
        assertEquals(skipped, ladder.sign().skippedCount());
        assertEquals(10L, ladder.sign().enteredTick());
        assertEquals(DegradeLevel.B3, ladder.sign().deepest());

        DegradeLadder.Advance backToB2 = ladder.noteEntered(DegradeLevel.B2, 12L);
        assertEquals(DegradeLevel.B3, backToB2.reached());
        assertTrue(backToB2.entered().isEmpty());
        assertEquals(DegradeLevel.B3, ladder.sign().deepest());
        assertEquals(10L, ladder.sign().enteredTick());
    }

    @Test
    void theSameRungDoesNotCountAgain() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B3, 10L);
        long entered = ladder.counters(DegradeLevel.B3).entered();

        DegradeLadder.Advance same = ladder.noteEntered(DegradeLevel.B3, 11L);
        assertTrue(same.entered().isEmpty());
        assertEquals(entered, ladder.counters(DegradeLevel.B3).entered());
        assertEquals(10L, ladder.sign().enteredTick());
    }

    @Test
    void aHigherTargetStillWalksInOrder() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        DegradeLadder.Advance first = ladder.noteEntered(DegradeLevel.B1, 10L);
        assertFalse(first.skipped());
        DegradeLadder.Advance next = ladder.noteEntered(DegradeLevel.B2, 11L);
        assertFalse(next.skipped());
        assertEquals(DegradeLevel.B2, next.reached());

        DegradeLadder.Advance skip = ladder.noteEntered(DegradeLevel.B4, 12L);
        assertEquals(DegradeLevel.B4, skip.reached());
        assertTrue(skip.skipped());
        assertEquals(java.util.List.of(DegradeLevel.B3, DegradeLevel.B4), skip.entered());
        assertEquals(DegradeLevel.B4, ladder.sign().deepest());
        assertEquals(1L, ladder.counters(DegradeLevel.B3).entered());
        assertEquals(1L, ladder.counters(DegradeLevel.B4).entered());
    }

    @Test
    void noneAndTheOrdinalZeroPathDoNotMoveTheLadder() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B3, 10L);
        long enteredTick = ladder.sign().enteredTick();
        DegradeLadder.Advance none = ladder.noteEntered(DegradeLevel.NONE, 11L);
        assertTrue(none.entered().isEmpty());
        assertEquals(DegradeLevel.B3, ladder.sign().deepest());
        assertEquals(enteredTick, ladder.sign().enteredTick());
        DegradeLadder.Advance nil = ladder.noteEntered(null, 12L);
        assertTrue(nil.entered().isEmpty());
        assertEquals(DegradeLevel.B3, ladder.sign().deepest());
        assertEquals(enteredTick, ladder.sign().enteredTick());
    }
}
