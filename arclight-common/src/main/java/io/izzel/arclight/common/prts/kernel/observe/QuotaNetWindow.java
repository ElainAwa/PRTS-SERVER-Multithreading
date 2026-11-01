/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.net.OutboundAcceptance;

/** The read-only network quota window: network wall time per segment second against the declared
 * tick budget. A completed over window arms outbound acceptance only while its product property is
 * on; a later under window clears it. The window itself changes no schedule and its over count is
 * bounded and published as a count. */
public final class QuotaNetWindow {

    private static long totalStartTick = -1L;
    private static long windowStartTick = -1L;
    private static long lastNetNanos;
    private static boolean warmed;
    private static long overCount;

    private QuotaNetWindow() {
    }

    /** Closes one window when due. The first call only seeds the baseline; over is counted once per
     * completed non-warmup window. The over state is applied to acceptance only through the guarded
     * product setter. */
    public static synchronized void noteTick(long tickIndex, long netNanos, long windowTicks,
                                             long warmupTicks, double limitMs) {
        long width = Math.max(1L, windowTicks);
        if (totalStartTick < 0L) {
            totalStartTick = tickIndex;
            windowStartTick = tickIndex;
            lastNetNanos = netNanos;
            return;
        }
        if (tickIndex - windowStartTick < width) {
            return;
        }
        long usedNanos = Math.max(0L, netNanos - lastNetNanos);
        double seconds = width / 20.0;
        double usedMsPerSecond = (usedNanos / 1_000_000.0) / seconds;
        boolean warm = !warmed
            && tickIndex - totalStartTick < Math.max(0L, warmupTicks) + width;
        if (!warm) {
            warmed = true;
            boolean over = limitMs > 0.0 && usedMsPerSecond > limitMs;
            if (over) {
                overCount++;
            }
            OutboundAcceptance.applyNetQuotaDefer(over);
        } else {
            OutboundAcceptance.applyNetQuotaDefer(false);
        }
        windowStartTick = tickIndex;
        lastNetNanos = netNanos;
    }

    public static synchronized long overCount() {
        return overCount;
    }

    public static synchronized void reset() {
        totalStartTick = -1L;
        windowStartTick = -1L;
        lastNetNanos = 0L;
        warmed = false;
        overCount = 0L;
        OutboundAcceptance.applyNetQuotaDefer(false);
    }
}
