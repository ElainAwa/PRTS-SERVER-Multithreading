/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The scale window: how many sampled ticks carried more real entities than the declared budget and
 * how many entity merges the scale overrun itself caused. The window is sampled once per tick after
 * the entity domain ran; a merge is queued for the very tick and window it was admitted under and
 * committed only by that tick's sample, so an unrelated action, a late report, a replay or a report
 * from a reset generation cannot look like a scale action. The counters change no scheduling
 * decision and hold no reference to the live world. */
public final class ScaleBudgetReadings {

    private static long windowStartTick = -1L;
    private static long overBudgetTicks;
    private static long degradedCount;
    private static long generation;
    private static long lastSampleTick = -1L;
    private static long pendingTick = -1L;
    private static long pendingWindowStart = -1L;
    private static long pendingGeneration = -1L;
    private static int pendingMerges;

    private ScaleBudgetReadings() {
    }

    /** The generation of the current reading; a reset advances it, so a report admitted under the
     * old generation cannot be committed after it. */
    public static synchronized long generation() {
        return generation;
    }

    /** The start tick the window of this tick belongs to: a tick at or after the current window's
     * width opens a new window. The answer is the one the next sample of this tick uses. */
    public static synchronized long windowStartFor(long tickIndex, long windowTicks) {
        long width = Math.max(1L, windowTicks);
        if (windowStartTick < 0L || tickIndex - windowStartTick >= width) {
            return tickIndex;
        }
        return windowStartTick;
    }

    /** Queues the count of real adjacent merges the scale trigger of one tick caused. One tick
     * accepts one report; a replay is refused, so a count can never be doubled. */
    public static synchronized boolean noteScaleMerged(long tickIndex, long windowStart,
                                                       long resetGeneration, int mergeReceipts) {
        if (mergeReceipts <= 0 || resetGeneration != generation || tickIndex <= lastSampleTick) {
            return false;
        }
        if (pendingTick == tickIndex) {
            return false;
        }
        pendingTick = tickIndex;
        pendingWindowStart = windowStart;
        pendingGeneration = resetGeneration;
        pendingMerges = mergeReceipts;
        return true;
    }

    /** Samples one tick; a window boundary resets the two window counters, then the report queued
     * for this very tick and window is committed only while the window is over its budget. */
    public static synchronized void noteTick(long tickIndex, long entityCount, long budget,
                                             long windowTicks) {
        long start = windowStartFor(tickIndex, windowTicks);
        if (start != windowStartTick) {
            windowStartTick = start;
            overBudgetTicks = 0L;
            degradedCount = 0L;
        }
        if (entityCount > budget) {
            overBudgetTicks++;
        }
        if (pendingTick == tickIndex && pendingWindowStart == start
            && pendingGeneration == generation && overBudgetTicks > 0L) {
            degradedCount += pendingMerges;
        }
        pendingTick = -1L;
        pendingWindowStart = -1L;
        pendingGeneration = -1L;
        pendingMerges = 0;
        lastSampleTick = tickIndex;
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
        generation++;
        lastSampleTick = -1L;
        pendingTick = -1L;
        pendingWindowStart = -1L;
        pendingGeneration = -1L;
        pendingMerges = 0;
    }
}
