/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam one non-passenger entity tick reports its span through, with the instance it ran for.
 * The call is not changed and the seam is a no-op while no tap is installed. */
public final class PrtsEntityCosts {

    public interface CostTap {

        /** @param entity the instance that ticked; @param level the level it ticked in; @param worldId
         * the dimension; @param wallNanos the span; @param cpuNanos its thread cpu time, or -1 */
        void entityTick(Object entity, Object level, String worldId, long wallNanos, long cpuNanos);
    }

    private static volatile CostTap tap;

    private PrtsEntityCosts() {
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

    public static void entityTick(Object entity, Object level, String worldId, long wallNanos,
                                  long cpuNanos) {
        CostTap watcher = tap;
        if (watcher != null) {
            watcher.entityTick(entity, level, worldId, wallNanos, cpuNanos);
        }
    }
}
