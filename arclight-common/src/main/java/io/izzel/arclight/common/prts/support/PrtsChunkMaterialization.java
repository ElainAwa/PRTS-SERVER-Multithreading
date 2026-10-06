/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.atomic.AtomicLong;

/** The seam a chunk reports its materialization through: one call where the pipeline replaces a
 * chunk's proto form with the materialized level chunk, which is the only point where a completed
 * materialization is observable without a queue standing in for it. The call reads and does not act,
 * and it is a no-op while no watcher is installed. */
public final class PrtsChunkMaterialization {

    /** The generation status whose completion materializes a chunk. */
    public static final String STATUS_FULL = "full";

    /** The name of the reading this producer feeds: the materialization itself, not the mailbox
     * flow, the demand futures or the intent channel that merely surround it. */
    public static final String SOURCE = "chunk.materialized";

    public interface MaterializationTap {

        /** One chunk was materialized: {@code ticket} identifies the completion and is reused by a
         * repeat of the same event, {@code worldId} and {@code chunkPos} are read from the
         * materialized chunk, {@code status} is the generation status it completed, and
         * {@code generation} is the generation cycle the holder was in. */
        void materialized(long ticket, String worldId, long chunkPos, String status, int generation);
    }

    private static final AtomicLong TICKETS = new AtomicLong();
    private static volatile MaterializationTap tap;

    private PrtsChunkMaterialization() {
    }

    /** @return the identity of the next completion */
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
