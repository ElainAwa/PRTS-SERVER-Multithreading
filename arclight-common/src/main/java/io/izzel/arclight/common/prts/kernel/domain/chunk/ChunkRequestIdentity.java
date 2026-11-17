/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import java.util.Objects;

/** The identity of one chunk request: the world incarnation, the chunk position, the holder
 * generation the request was bound to, the status the subscriber needs, its subscriber identity and
 * its deadline. A result belongs to one identity; a result of an older holder generation is stale. */
public record ChunkRequestIdentity(long worldIncarnation, int chunkX, int chunkZ, long holderGen,
                                   String targetStatus, String subscriberId,
                                   long deadlineNanos) implements Comparable<ChunkRequestIdentity> {

    public ChunkRequestIdentity {
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(subscriberId, "subscriberId");
        if (worldIncarnation < 0L || holderGen < 0L || deadlineNanos < 0L) {
            throw new IllegalArgumentException("a request cannot name a negative generation");
        }
        if (targetStatus.isEmpty() || subscriberId.isEmpty()) {
            throw new IllegalArgumentException("a request names its status and its subscriber");
        }
    }

    /** The packed chunk position of this request. */
    public long chunkPos() {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Whether two requests share the merged production unit: same world, chunk and holder. */
    public boolean sameProduction(ChunkRequestIdentity other) {
        return other != null && worldIncarnation == other.worldIncarnation && chunkX == other.chunkX
            && chunkZ == other.chunkZ && holderGen == other.holderGen;
    }

    @Override
    public int compareTo(ChunkRequestIdentity other) {
        int world = Long.compare(worldIncarnation, other.worldIncarnation);
        if (world != 0) {
            return world;
        }
        int x = Integer.compare(chunkX, other.chunkX);
        if (x != 0) {
            return x;
        }
        int z = Integer.compare(chunkZ, other.chunkZ);
        if (z != 0) {
            return z;
        }
        int holder = Long.compare(holderGen, other.holderGen);
        if (holder != 0) {
            return holder;
        }
        int status = targetStatus.compareTo(other.targetStatus);
        return status != 0 ? status : subscriberId.compareTo(other.subscriberId);
    }
}
