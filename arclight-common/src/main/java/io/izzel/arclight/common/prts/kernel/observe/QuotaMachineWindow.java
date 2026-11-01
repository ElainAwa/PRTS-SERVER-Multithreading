/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The read-only MACHINE quota window: the neighbour-update wall time per segment second against the
 * declared global tick budget. A completed non-warmup window is counted at most once, and the window
 * never acts: MACHINE has no safe action in this build, so the counter is the only output and a
 * positive over is a named refusal for acceptance, not a degradation. */
public final class QuotaMachineWindow {

    private static long totalStartTick = -1L;
    private static long windowStartTick = -1L;
    private static long lastMachineNanos;
    private static boolean warmed;
    private static long overCount;

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
            if (limitMs > 0.0 && usedMsPerSecond > limitMs) {
                overCount++;
            }
        }
        windowStartTick = tickIndex;
        lastMachineNanos = machineNanos;
    }

    public static synchronized long overCount() {
        return overCount;
    }

    public static synchronized void reset() {
        totalStartTick = -1L;
        windowStartTick = -1L;
        lastMachineNanos = 0L;
        warmed = false;
        overCount = 0L;
    }
}
