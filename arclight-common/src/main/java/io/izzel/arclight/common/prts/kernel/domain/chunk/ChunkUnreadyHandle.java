/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import java.util.Objects;

/** The explicit handle an unready chunk read returns: which chunk, which holder generation, the
 * status that was asked for and the reason it is not ready. The handle is never a value: it does not
 * carry AIR, an empty list or a FULL status, and a retryable handle says a later ask may answer. */
public record ChunkUnreadyHandle(long worldIncarnation, int chunkX, int chunkZ, long holderGen,
                                 String requestedStatus, Reason reason, boolean retryable) {

    /** Why one read had no ready chunk; the set is closed and never a fake status. */
    public enum Reason {
        CHUNK_NOT_LOADED,
        STATUS_NOT_READY,
        HOLDER_GENERATION_STALE
    }

    public ChunkUnreadyHandle {
        Objects.requireNonNull(requestedStatus, "requestedStatus");
        Objects.requireNonNull(reason, "reason");
        if (requestedStatus.isEmpty()) {
            throw new IllegalArgumentException("an unready handle names the requested status");
        }
    }

    /** The direct answer of one read: a ready chunk is a direct path, an unready one is a handle. */
    public record Decision(boolean direct, ChunkUnreadyHandle handle) {

        public Decision {
            if (direct == (handle != null)) {
                throw new IllegalArgumentException("a read is either direct or an unready handle");
            }
        }

        public boolean unready() {
            return handle != null;
        }
    }

    /** Decides one read without loading, joining or fabricating: only a loaded and ready chunk is
     * direct; every other answer is the explicit handle of the reason. */
    public static Decision decide(long worldIncarnation, int chunkX, int chunkZ, long holderGen,
                                  String requestedStatus, boolean loaded, boolean statusReady,
                                  boolean holderCurrent) {
        if (!holderCurrent) {
            return new Decision(false, new ChunkUnreadyHandle(worldIncarnation, chunkX, chunkZ,
                holderGen, requestedStatus, Reason.HOLDER_GENERATION_STALE, false));
        }
        if (!loaded) {
            return new Decision(false, new ChunkUnreadyHandle(worldIncarnation, chunkX, chunkZ,
                holderGen, requestedStatus, Reason.CHUNK_NOT_LOADED, true));
        }
        if (!statusReady) {
            return new Decision(false, new ChunkUnreadyHandle(worldIncarnation, chunkX, chunkZ,
                holderGen, requestedStatus, Reason.STATUS_NOT_READY, true));
        }
        return new Decision(true, null);
    }
}
