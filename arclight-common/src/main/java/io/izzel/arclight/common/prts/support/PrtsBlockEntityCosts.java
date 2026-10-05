/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam one block entity tick reports its span through, with the instance it ran for. The call
 * is not changed: the span is read around it and reported after it. No-op while no tap is installed. */
public final class PrtsBlockEntityCosts {

    public interface CostTap {

        /** @param blockEntity the ticker that ran; @param level the level it ran in, for a position
         * lookup; @param worldId the dimension; @param wallNanos the span; @param cpuNanos its thread
         * cpu time, or -1 */
        void blockEntityTick(Object blockEntity, Object level, String worldId, long wallNanos,
                             long cpuNanos);

        /** The span the level reported around the whole block entity list of one tick. */
        void segmentTick(String worldId, long wallNanos);
    }

    private static volatile CostTap tap;

    private PrtsBlockEntityCosts() {
    }

    public static void install(CostTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    public static CostTap watcher() {
        return tap;
    }

    public static void blockEntityTick(Object blockEntity, Object level, String worldId,
                                       long wallNanos, long cpuNanos) {
        CostTap watcher = tap;
        if (watcher != null) {
            watcher.blockEntityTick(blockEntity, level, worldId, wallNanos, cpuNanos);
        }
    }

    public static void segmentTick(String worldId, long wallNanos) {
        CostTap watcher = tap;
        if (watcher != null) {
            watcher.segmentTick(worldId, wallNanos);
        }
    }
}
