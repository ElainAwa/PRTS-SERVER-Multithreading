/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam the chunk cache reports its demand side through: what was asked of it, what the ask was
 * answered with, and the futures it handed out. The calls are not changed and nothing is cancelled,
 * delayed or reordered; every call is a no-op while no tap is installed. A tap that has no use for
 * the chunk implements the entries that do not name it, and is told about each ask once. A tap
 * that throws is not the ask failing: its count is dropped, and a stamp it cannot answer reads -1,
 * which is no observation time to report -- not a refusal code, not a failure of the host and not a
 * new reading key. */
public final class PrtsChunkDemand {

    public interface DemandTap {

        /** One ask entered the cache. @param blocking whether the caller waits for the answer */
        void demandStarted(String worldId, String status, boolean blocking);

        /** One ask entered the cache, with the chunk it named; the default keeps the old count. */
        default void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                   boolean requireChunk) {
            demandStarted(worldId, status, requireChunk);
        }

        /** The ask returned. @param satisfied whether it carried a chunk at the wanted status */
        void demandFinished(String worldId, String status, boolean blocking, boolean satisfied);

        /** A future-returning ask entered the cache. */
        void futureOpened(String worldId, String status);

        /** A future-returning ask entered the cache, with the chunk it named; the default keeps the
         * old count. */
        default void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                      boolean requireChunk) {
            futureOpened(worldId, status);
        }

        /** @return the stamp the matching open left, or -1 when there is no observation time */
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
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandStarted(worldId, status, blocking);
        } catch (Throwable ignored) {
        }
    }

    public static void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                     boolean requireChunk) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandStarted(worldId, chunkX, chunkZ, status, requireChunk);
        } catch (Throwable ignored) {
            // A watcher that throws is not the ask failing: the count is dropped, the ask goes on.
        }
    }

    public static void demandFinished(String worldId, String status, boolean blocking,
                                      boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandFinished(worldId, status, blocking, satisfied);
        } catch (Throwable ignored) {
        }
    }

    public static void futureOpened(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureOpened(worldId, status);
        } catch (Throwable ignored) {
        }
    }

    public static void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                        boolean requireChunk) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.demandDescriptor(worldId, chunkX, chunkZ, status, requireChunk);
        } catch (Throwable ignored) {
            // A watcher that throws is not the ask failing: the count is dropped, the ask goes on.
        }
    }

    public static long futureOpenedAt() {
        DemandTap watcher = tap;
        if (watcher == null) {
            return -1L;
        }
        try {
            return watcher.futureOpenedAt();
        } catch (Throwable ignored) {
            // A watch that cannot answer leaves no observation time: -1, which is not a refusal.
            return -1L;
        }
    }

    public static void futureTaken(String worldId, String status) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureTaken(worldId, status);
        } catch (Throwable ignored) {
        }
    }

    public static void futureCompleted(String worldId, String status, long startedNanos,
                                       boolean satisfied) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureCompleted(worldId, status, startedNanos, satisfied);
        } catch (Throwable ignored) {
        }
    }
}
