/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.atomic.AtomicLong;

/** The seam a chunk reports its materialization through: a read-only call, a no-op while no watcher is installed. */
public final class PrtsChunkMaterialization {

    /** The generation status whose completion materializes a chunk. */
    public static final String STATUS_FULL = "full";

    /** The name of the reading this producer feeds: the materialization itself. */
    public static final String SOURCE = "chunk.materialized";

    public interface MaterializationTap {

        /** One chunk was materialized; {@code ticket} is reused by a repeat of the same event. */
        void materialized(long ticket, String worldId, long chunkPos, String status, int generation);
    }

    private static final AtomicLong TICKETS = new AtomicLong();
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

    public static void materialized(long ticket, String worldId, long chunkPos, String status,
                                    int generation) {
        MaterializationTap watcher = tap;
        if (watcher != null) {
            watcher.materialized(ticket, worldId, chunkPos, status, generation);
        }
    }
}
