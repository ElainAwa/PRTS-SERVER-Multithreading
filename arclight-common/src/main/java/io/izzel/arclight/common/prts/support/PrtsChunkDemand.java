/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam the chunk cache reports its demand side through: what was asked of it, what the ask was
 * answered with, and the futures it handed out. The calls are not changed and nothing is cancelled,
 * delayed or reordered; every call is a no-op while no tap is installed. */
public final class PrtsChunkDemand {

    public interface DemandTap {

        /** One ask entered the cache. @param blocking whether the caller waits for the answer */
        void demandStarted(String worldId, String status, boolean blocking);

        /** The ask returned. @param satisfied whether it carried a chunk at the wanted status */
        void demandFinished(String worldId, String status, boolean blocking, boolean satisfied);

        /** A future-returning ask entered the cache. */
        void futureOpened(String worldId, String status);

        /** @return the stamp the matching open left, or -1 when there is none */
        long futureOpenedAt();

        /** The future was handed to the caller; it is outstanding until it completes. */
        void futureTaken(String worldId, String status);

        /** The future completed, satisfied or not. */
        void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied);
    }

    private static volatile DemandTap tap;

    private PrtsChunkDemand() {
    }

    public static void install(DemandTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    public static DemandTap watcher() {
        return tap;
    }

    public static void demandStarted(String worldId, String status, boolean blocking) {
        DemandTap watcher = tap;
        if (watcher != null) {
            watcher.demandStarted(worldId, status, blocking);
        }
    }

    public static void demandFinished(String worldId, String status, boolean blocking,
                                      boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher != null) {
            watcher.demandFinished(worldId, status, blocking, satisfied);
        }
    }

    public static void futureOpened(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher != null) {
            watcher.futureOpened(worldId, status);
        }
    }

    public static long futureOpenedAt() {
        DemandTap watcher = tap;
        return watcher == null ? -1L : watcher.futureOpenedAt();
    }

    public static void futureTaken(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher != null) {
            watcher.futureTaken(worldId, status);
        }
    }

    public static void futureCompleted(String worldId, String status, long startedNanos,
                                       boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher != null) {
            watcher.futureCompleted(worldId, status, startedNanos, satisfied);
        }
    }
}
