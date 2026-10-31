/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The read-only entity quota window: entity wall time per segment second against a declared
 * quota, counted once per completed window. It changes no scheduling decision and publishes only
 * the over count; the occupancy itself stays internal. */
public final class QuotaEntityWindow {

    private static long totalStartTick = -1L;
    private static long windowStartTick = -1L;
    private static long lastEntityNanos;
    private static boolean warmed;
    private static long overCount;

    private QuotaEntityWindow() {
    }

    public static synchronized void noteTick(long tickIndex, long entityNanos, long windowTicks,
                                             long warmupTicks, int quotaMs) {
        long width = Math.max(1L, windowTicks);
        if (totalStartTick < 0L) {
            totalStartTick = tickIndex;
            windowStartTick = tickIndex;
            lastEntityNanos = entityNanos;
            return;
        }
        if (tickIndex - windowStartTick < width) {
            return;
        }
        long usedNanos = Math.max(0L, entityNanos - lastEntityNanos);
        double seconds = width / 20.0;
        double usedMsPerSecond = (usedNanos / 1_000_000.0) / seconds;
        boolean warm = !warmed
            && tickIndex - totalStartTick < Math.max(0L, warmupTicks) + width;
        if (!warm) {
            warmed = true;
            if (quotaMs > 0 && usedMsPerSecond > quotaMs) {
                overCount++;
            }
        }
        windowStartTick = tickIndex;
        lastEntityNanos = entityNanos;
    }

    public static synchronized long overCount() {
        return overCount;
    }

    public static synchronized void reset() {
        totalStartTick = -1L;
        windowStartTick = -1L;
        lastEntityNanos = 0L;
        warmed = false;
        overCount = 0L;
    }
}
