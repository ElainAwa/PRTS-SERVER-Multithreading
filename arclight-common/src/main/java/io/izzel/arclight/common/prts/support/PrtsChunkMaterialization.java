/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** The seam a chunk reports its materialization through: a read-only call, a no-op while no watcher is installed. */
public final class PrtsChunkMaterialization {

    /** The generation status whose completion materializes a chunk. */
    public static final String STATUS_FULL = "full";

    /** The name of the reading this producer feeds: the materialization itself. */
    public static final String SOURCE = "chunk.materialized";

    /** Where a callback's thread identity is read from: the running server's own running thread. */
    public static final String SERVER_THREAD_SOURCE = "server.getRunningThread";

    public interface MaterializationTap {

        /** One chunk was materialized; {@code ticket} is reused by a repeat of the same event. */
        void materialized(long ticket, String worldId, long chunkPos, String status, int generation);

        /** One completion's callback, reported with the role of the thread that ran it: whether the
         * running server owns that thread, whether the future was already complete when the callback
         * was registered, and whether the completion carried a failure. The body must not throw. */
        default void callbackThread(String worldId, long threadId, boolean serverOwned,
                boolean registeredLate, boolean failed, boolean identityUnknown) {
        }
    }

    private static final AtomicLong TICKETS = new AtomicLong();
    private static final LongAdder CALLBACK_FAILURES = new LongAdder();
    private static final LongAdder MATERIALIZED = new LongAdder();
    private static volatile MaterializationTap tap;

    private PrtsChunkMaterialization() {
    }

    public static long nextTicket() {
        return TICKETS.incrementAndGet();
    }

    public static void install(MaterializationTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    public static MaterializationTap watcher() {
        return tap;
    }

    public static long materializedCount() {
        return MATERIALIZED.sum();
    }

    public static void materialized(long ticket, String worldId, long chunkPos, String status,
                                    int generation) {
        MATERIALIZED.increment();
        MaterializationTap watcher = tap;
        if (watcher != null) {
            watcher.materialized(ticket, worldId, chunkPos, status, generation);
        }
    }

    /** Reports one completion's callback against the thread the running server owns; a report that
     * throws is counted here and dropped, never rethrown into the future's dependent stage. */
    public static void callbackThread(String worldId, long threadId, boolean serverOwned,
                                      boolean registeredLate, boolean failed,
                                      boolean identityUnknown) {
        MaterializationTap watcher = tap;
        if (watcher == null) {
            return;
        }
        try {
            watcher.callbackThread(worldId, threadId, serverOwned, registeredLate, failed,
                identityUnknown);
        } catch (Throwable thrown) {
            CALLBACK_FAILURES.increment();
        }
    }

    /** Counts a callback report that could not be delivered. */
    public static void callbackReportFailed() {
        CALLBACK_FAILURES.increment();
    }

    /** @return how many callback reports failed to be delivered */
    public static long callbackFailures() {
        return CALLBACK_FAILURES.sum();
    }
}
