/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam the world's tick path reports two wall-clock spans through: one entity row and the
 * block entities of one level. Both are no-ops while no tap is installed. */
public final class PrtsSelfCosts {

    public interface CostTap {

        /** @param worldId the dimension the row ticks in; @param rowRef a short name of the row */
        void entityRow(String worldId, String rowRef, long nanos);

        /** @param worldId the dimension whose block entities ticked; @param nanos the span */
        void blockEntities(String worldId, long nanos);
    }

    private static volatile CostTap tap;

    private PrtsSelfCosts() {
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

    public static void entityRow(String worldId, String rowRef, long nanos) {
        CostTap watcher = tap;
        if (watcher != null) {
            watcher.entityRow(worldId, rowRef, nanos);
        }
    }

    public static void blockEntities(String worldId, long nanos) {
        CostTap watcher = tap;
        if (watcher != null) {
            watcher.blockEntities(worldId, nanos);
        }
    }
}
