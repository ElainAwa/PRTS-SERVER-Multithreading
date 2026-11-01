/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scale window counts only the real merges queued for its own tick and window: an over-budget
 * tick without a merge stays undegraded, a replay or a stale report is refused, and a window
 * boundary resets both counters before the new window's report is committed. */
class P2F06eScaleBudgetTest {

    @Test
    void belowBudgetKeepsBothCountersZero() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 100L, 200L, 10L);
        assertEquals(0L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void anOverBudgetTickWithoutAMergeStaysUndegraded() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 10L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount(),
            "no merge happened, so nothing may look like a scale action");
    }

    @Test
    void aQueuedMergeOfAnOverBudgetTickIsCountedOnce() {
        ScaleBudgetReadings.reset();
        long generation = ScaleBudgetReadings.generation();
        assertTrue(ScaleBudgetReadings.noteScaleMerged(1L, 1L, generation, 3));
        assertFalse(ScaleBudgetReadings.noteScaleMerged(1L, 1L, generation, 5),
            "one tick accepts one report, so a replay cannot double the count");
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 10L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(3L, ScaleBudgetReadings.degradedCount());
        assertFalse(ScaleBudgetReadings.noteScaleMerged(1L, 1L, generation, 3),
            "a report for an already sampled tick is refused");
    }

    @Test
    void aQueuedMergeOfAnUnderBudgetTickIsNotCounted() {
        ScaleBudgetReadings.reset();
        assertTrue(ScaleBudgetReadings.noteScaleMerged(1L, 1L, ScaleBudgetReadings.generation(), 2));
        ScaleBudgetReadings.noteTick(1L, 100L, 200L, 10L);
        assertEquals(0L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void aQueuedMergeForAnotherTickIsDropped() {
        ScaleBudgetReadings.reset();
        assertTrue(ScaleBudgetReadings.noteScaleMerged(1L, 1L, ScaleBudgetReadings.generation(), 2));
        ScaleBudgetReadings.noteTick(2L, 300L, 200L, 10L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount(), "the report of another tick is stale");
    }

    @Test
    void aResetDropsTheQueueAndAdvancesTheGeneration() {
        ScaleBudgetReadings.reset();
        long before = ScaleBudgetReadings.generation();
        assertTrue(ScaleBudgetReadings.noteScaleMerged(1L, 1L, before, 4));
        ScaleBudgetReadings.reset();
        assertNotEquals(before, ScaleBudgetReadings.generation());
        assertFalse(ScaleBudgetReadings.noteScaleMerged(1L, 1L, before, 4),
            "a report admitted before the reset cannot be committed after it");
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 10L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void aWindowBoundaryResetsBothCountersAndCountsTheNewWindow() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 2L);
        assertTrue(ScaleBudgetReadings.noteScaleMerged(2L, 1L, ScaleBudgetReadings.generation(), 4));
        ScaleBudgetReadings.noteTick(2L, 300L, 200L, 2L);
        assertEquals(2L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(4L, ScaleBudgetReadings.degradedCount());
        assertTrue(ScaleBudgetReadings.noteScaleMerged(3L, 3L, ScaleBudgetReadings.generation(), 5));
        ScaleBudgetReadings.noteTick(3L, 300L, 200L, 2L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks(), "the third tick opens a new window");
        assertEquals(5L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void theWindowStartIsTheOneTheSampleUses() {
        ScaleBudgetReadings.reset();
        assertEquals(5L, ScaleBudgetReadings.windowStartFor(5L, 10L));
        ScaleBudgetReadings.noteTick(5L, 100L, 200L, 10L);
        assertEquals(5L, ScaleBudgetReadings.windowStartFor(9L, 10L));
        assertEquals(15L, ScaleBudgetReadings.windowStartFor(15L, 10L));
    }
}
