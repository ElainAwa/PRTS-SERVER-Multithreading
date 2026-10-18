/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.atomic.AtomicLong;

/** The seam the chunk cache reports its demand side through: what was asked, what answered it, and
 * the futures it handed out, each named for its call. Every call is a no-op while no tap is
 * installed, and a tap that throws is not the ask failing: its count is dropped and a stamp it
 * cannot answer reads -1, which is no observation time and not a refusal code. */
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

        /** A named future-returning ask; the default keeps the old count. */
        default void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                      boolean requireChunk, long token) {
            demandDescriptor(worldId, chunkX, chunkZ, status, requireChunk);
        }

        /** A named ask that also names the task it was made under; the default drops the task. */
        default void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                      boolean requireChunk, long token, Object taskToken) {
            demandDescriptor(worldId, chunkX, chunkZ, status, requireChunk, token);
        }

        /** A named future was handed to the caller; the default keeps the old count. */
        default void futureTaken(String worldId, String status, long token) {
            futureTaken(worldId, status);
        }

        /** One future completed, named by the call it belongs to; the default keeps the old count. */
        default void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied,
                                     long token) {
            futureCompleted(worldId, status, startedNanos, satisfied);
        }

        /** The call that named this token registered its completion watch, or failed to; a tap
         * written before this stage has nothing to do, so the default is empty. */
        default void registrationConfirmed(String worldId, String status, long token, boolean ok) {
        }
    }

    /** The value that names no call: never issued, and nothing else may stand in for it. */
    public static final long NO_TOKEN = 0L;

    private static final AtomicLong callTokens = new AtomicLong();
    private static volatile boolean tokensExhausted;

    private static long nextToken() {
        if (tokensExhausted) {
            return NO_TOKEN;
        }
        long token = callTokens.incrementAndGet();
        if (token <= 0L) {
            tokensExhausted = true;
            return NO_TOKEN;
        }
        return token;
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

    /** The entry a future-returning ask arrives through: it names the call before any tap code and
     * hands the name back for that call's return.
     * @return the name of this call, or {@link #NO_TOKEN} when nothing is installed */
    public static long demandDescriptorToken(String worldId, int chunkX, int chunkZ, String status,
                                             boolean requireChunk) {
        return demandDescriptorToken(worldId, chunkX, chunkZ, status, requireChunk, null);
    }

    /** The entry a future-returning ask arrives through, naming the call and the task it was made
     * under.
     * @return the name of this call, or {@link #NO_TOKEN} when nothing is installed */
    public static long demandDescriptorToken(String worldId, int chunkX, int chunkZ, String status,
                                             boolean requireChunk, Object taskToken) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return NO_TOKEN;
        }
        long token = nextToken();
        try {
            watcher.demandDescriptor(worldId, chunkX, chunkZ, status, requireChunk, token, taskToken);
        } catch (Throwable ignored) {
            // A watcher that throws is not the ask failing: the name is this call's either way.
        }
        return token;
    }

    /** The future of the call that carried this name was handed to its caller. */
    public static void futureTaken(String worldId, String status, long token) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureTaken(worldId, status, token);
        } catch (Throwable ignored) {
        }
    }

    /** The future of the call that carried this name completed, satisfied or not. */
    public static void futureCompleted(String worldId, String status, long startedNanos,
                                       boolean satisfied, long token) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.futureCompleted(worldId, status, startedNanos, satisfied, token);
        } catch (Throwable ignored) {
        }
    }

    /** The call that carried this name reached the end of its completion watch registration; only
     * then may what it captured be handed on. */
    public static void registrationConfirmed(String worldId, String status, long token, boolean ok) {
        DemandTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.registrationConfirmed(worldId, status, token, ok);
        } catch (Throwable ignored) {
        }
    }
}
