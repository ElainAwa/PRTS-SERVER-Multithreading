/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The long-window health slopes: entity rows, world transitions, in-flight futures and arena
 * claims. It samples only and changes no scheduling decision; a window that has not finished its
 * warm-up publishes nothing. */
public final class HealthReadings {

    private static long windowStartTick = -1L;
    private static long startedTick = -1L;
    private static boolean warmed;
    private static long firstEntity;
    private static long firstChurn;
    private static long firstInflight;
    private static long firstAlloc;
    private static long lastEntity;
    private static long lastChurn;
    private static long lastInflight;
    private static long lastAlloc;
    private static boolean published;
    private static double entitySlope;
    private static double churnSlope;
    private static double inflightSlope;
    private static double allocSlope;

    private HealthReadings() {
    }

    /** Samples one tick. A finished non-warm-up window replaces the four published slopes. */
    public static synchronized void noteTick(long tickIndex, long entity, long churn,
                                             long inflight, long alloc, long windowTicks,
                                             long warmupTicks) {
        long width = Math.max(1L, windowTicks);
        if (windowStartTick < 0L) {
            windowStartTick = tickIndex;
            startedTick = tickIndex;
            firstEntity = lastEntity = entity;
            firstChurn = lastChurn = churn;
            firstInflight = lastInflight = inflight;
            firstAlloc = lastAlloc = alloc;
            return;
        }
        lastEntity = entity;
        lastChurn = churn;
        lastInflight = inflight;
        lastAlloc = alloc;
        long elapsed = tickIndex - windowStartTick;
        if (elapsed < width) {
            return;
        }
        boolean warm = !warmed
            && tickIndex - startedTick < Math.max(0L, warmupTicks) + width;
        if (!warm) {
            warmed = true;
            entitySlope = (lastEntity - firstEntity) / (double) width;
            churnSlope = (lastChurn - firstChurn) / (double) width;
            inflightSlope = (lastInflight - firstInflight) / (double) width;
            allocSlope = (lastAlloc - firstAlloc) / (double) width;
            published = true;
        }
        windowStartTick = tickIndex;
        firstEntity = lastEntity;
        firstChurn = lastChurn;
        firstInflight = lastInflight;
        firstAlloc = lastAlloc;
    }

    public static synchronized boolean published() {
        return published;
    }

    public static synchronized double entitySlope() {
        return entitySlope;
    }

    public static synchronized double churnSlope() {
        return churnSlope;
    }

    public static synchronized double inflightSlope() {
        return inflightSlope;
    }

    public static synchronized double allocSlope() {
        return allocSlope;
    }

    public static synchronized void reset() {
        windowStartTick = -1L;
        startedTick = -1L;
        warmed = false;
        firstEntity = firstChurn = firstInflight = firstAlloc = 0L;
        lastEntity = lastChurn = lastInflight = lastAlloc = 0L;
        published = false;
        entitySlope = churnSlope = inflightSlope = allocSlope = 0.0;
    }
}
