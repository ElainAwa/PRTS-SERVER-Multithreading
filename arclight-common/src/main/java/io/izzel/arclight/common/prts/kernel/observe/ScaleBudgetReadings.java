/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The read-only scale window: entity count over a declared budget and the B3/entity action count
 * in that window. It changes no scheduling decision and holds no reference to the live world. */
public final class ScaleBudgetReadings {

    private static long windowStartTick = -1L;
    private static long overBudgetTicks;
    private static long degradedCount;
    private static long lastB3Effective;

    private ScaleBudgetReadings() {
    }

    /** Samples one tick; a window boundary resets the two counters, never the input series. */
    public static synchronized void noteTick(long tickIndex, long entityCount, long budget,
                                             long windowTicks, long b3Effective) {
        long width = Math.max(1L, windowTicks);
        if (windowStartTick < 0L || tickIndex - windowStartTick >= width) {
            // Keep lastB3Effective across the boundary: a B3 action on the boundary tick must
            // still produce its delta, or the official over>0 && degraded>0 check fails falsely.
            windowStartTick = tickIndex;
            overBudgetTicks = 0L;
            degradedCount = 0L;
        }
        if (entityCount > budget) {
            overBudgetTicks++;
        }
        long delta = Math.max(0L, b3Effective - lastB3Effective);
        lastB3Effective = b3Effective;
        if (overBudgetTicks > 0L && delta > 0L) {
            degradedCount += delta;
        }
    }

    public static synchronized long overBudgetTicks() {
        return overBudgetTicks;
    }

    public static synchronized long degradedCount() {
        return degradedCount;
    }

    public static synchronized void reset() {
        windowStartTick = -1L;
        overBudgetTicks = 0L;
        degradedCount = 0L;
        lastB3Effective = 0L;
    }
}
