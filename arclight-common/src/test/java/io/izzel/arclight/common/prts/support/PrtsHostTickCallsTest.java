/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The independent call counter: it tallies both paths of one tick, forgets the tick before it and
 * keeps calls that arrive outside a tick apart from the rows of a decision. */
class PrtsHostTickCallsTest {

    @BeforeEach
    void clearCounters() {
        PrtsHostTickCalls.reset();
    }

    @Test
    void theCounterIsOffUnlessTheObservationFaceIsDeclared() {
        assertFalse(PrtsHostTickCalls.ARMED, "a test run must not carry the observation property");
        assertEquals("probe_calls=0 probe_path_calls=0 probe_ticks=0 probe_untracked=0",
            PrtsHostTickCalls.evidence());
    }

    @Test
    void oneTickTalliesEveryCallOfEveryRowAndPath() {
        PrtsHostTickCalls.beginTick();
        PrtsHostTickCalls.noteBodyRow(7);
        PrtsHostTickCalls.noteBodyRow(7);
        PrtsHostTickCalls.notePathRow(7);
        PrtsHostTickCalls.notePathRow(9);
        assertEquals(2, PrtsHostTickCalls.bodyCallsOf(7), "a second body call was not counted");
        assertEquals(1, PrtsHostTickCalls.pathCallsOf(7));
        assertEquals(0, PrtsHostTickCalls.bodyCallsOf(9), "a row that only ran the host path "
            + "reported a body call");
        assertEquals(1, PrtsHostTickCalls.pathCallsOf(9));
        assertEquals(0, PrtsHostTickCalls.pathCallsOf(11), "a row that never ticked reported a call");
        assertEquals("probe_calls=2 probe_path_calls=2 probe_ticks=1 probe_untracked=0",
            PrtsHostTickCalls.evidence());
    }

    @Test
    void theNextTickStartsFromAnEmptyTally() {
        PrtsHostTickCalls.beginTick();
        PrtsHostTickCalls.noteBodyRow(7);
        PrtsHostTickCalls.notePathRow(7);
        PrtsHostTickCalls.endTick();
        PrtsHostTickCalls.beginTick();
        assertEquals(0, PrtsHostTickCalls.bodyCallsOf(7), "the row kept the calls of the tick before");
        assertEquals(0, PrtsHostTickCalls.pathCallsOf(7));
        PrtsHostTickCalls.noteBodyRow(8);
        assertEquals(1, PrtsHostTickCalls.bodyCallsOf(8));
        assertEquals("probe_calls=2 probe_path_calls=1 probe_ticks=2 probe_untracked=0",
            PrtsHostTickCalls.evidence(), "the process total forgot a call");
    }

    @Test
    void aCallOutsideATickIsCountedApartAndNamesNoRow() {
        PrtsHostTickCalls.beginTick();
        PrtsHostTickCalls.endTick();
        PrtsHostTickCalls.noteBodyRow(7);
        PrtsHostTickCalls.notePathRow(7);
        assertEquals("probe_calls=1 probe_path_calls=0 probe_ticks=1 probe_untracked=2",
            PrtsHostTickCalls.evidence(),
            "a call outside a tick was counted into the tick that was not open");
        assertEquals(0, PrtsHostTickCalls.bodyCallsOf(7));
        assertEquals(0, PrtsHostTickCalls.pathCallsOf(7));
    }
}
