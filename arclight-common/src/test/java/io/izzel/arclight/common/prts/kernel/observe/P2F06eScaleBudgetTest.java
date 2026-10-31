/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scale window is read-only: counts over-budget ticks and B3/entity effective deltas,
 * resets on the declared window, and never runs an action itself. */
class P2F06eScaleBudgetTest {

    @Test
    void belowBudgetKeepsBothCountersZero() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 100L, 200L, 10L, 0L);
        assertEquals(0L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void overBudgetWithoutB3DeltaStaysUndegraded() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 10L, 0L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void overBudgetWithB3DeltaCountsTheAction() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 10L, 0L);
        ScaleBudgetReadings.noteTick(2L, 300L, 200L, 10L, 3L);
        assertEquals(2L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(3L, ScaleBudgetReadings.degradedCount());
    }

    @Test
    void windowBoundaryResetsButCurrentTickIsStillCounted() {
        ScaleBudgetReadings.reset();
        ScaleBudgetReadings.noteTick(1L, 300L, 200L, 2L, 0L);
        ScaleBudgetReadings.noteTick(2L, 300L, 200L, 2L, 0L);
        ScaleBudgetReadings.noteTick(3L, 300L, 200L, 2L, 5L);
        assertEquals(1L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(5L, ScaleBudgetReadings.degradedCount());
        ScaleBudgetReadings.reset();
        assertEquals(0L, ScaleBudgetReadings.overBudgetTicks());
        assertEquals(0L, ScaleBudgetReadings.degradedCount());
    }
}
