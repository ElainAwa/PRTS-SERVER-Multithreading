/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The MACHINE quota window: the neighbour-update wall time per segment second against the declared
 * global tick budget. A completed non-warmup window is counted at most once and arms one bounded
 * action for the following ticks: the root neighbour updates are deferred to the tick's end safe
 * point while the window state is over. An under window releases the action, so the counter and the
 * action always follow the same window. */
public final class QuotaMachineWindow {

    private static long totalStartTick = -1L;
    private static long windowStartTick = -1L;
    private static long lastMachineNanos;
    private static boolean warmed;
    private static long overCount;
    private static boolean overActive;

    private QuotaMachineWindow() {
    }

    /** Closes one window when due. The first call only seeds the baseline; at most one over is
     * counted per completed non-warmup window, and no action is taken for it. */
    public static synchronized void noteTick(long tickIndex, long machineNanos, long windowTicks,
                                             long warmupTicks, double limitMs) {
        long width = Math.max(1L, windowTicks);
        if (totalStartTick < 0L) {
            totalStartTick = tickIndex;
            windowStartTick = tickIndex;
            lastMachineNanos = machineNanos;
            return;
        }
        if (tickIndex - windowStartTick < width) {
            return;
        }
        long usedNanos = Math.max(0L, machineNanos - lastMachineNanos);
        double seconds = width / 20.0;
        double usedMsPerSecond = (usedNanos / 1_000_000.0) / seconds;
        boolean warm = !warmed
            && tickIndex - totalStartTick < Math.max(0L, warmupTicks) + width;
        if (!warm) {
            warmed = true;
            overActive = limitMs > 0.0 && usedMsPerSecond > limitMs;
            if (overActive) {
                overCount++;
            }
        }
        windowStartTick = tickIndex;
        lastMachineNanos = machineNanos;
    }

    public static synchronized long overCount() {
        return overCount;
    }

    /** True while the newest completed non-warmup window is over; an under window clears it. */
    public static synchronized boolean overActive() {
        return overActive;
    }

    public static synchronized void reset() {
        totalStartTick = -1L;
        windowStartTick = -1L;
        lastMachineNanos = 0L;
        warmed = false;
        overCount = 0L;
        overActive = false;
    }
}
