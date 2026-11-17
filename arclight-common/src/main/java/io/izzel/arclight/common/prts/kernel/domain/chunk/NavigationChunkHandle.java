/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

/** The navigation answer for one target chunk: a path request gets the direct answer only when the
 * target chunk is loaded, reached the wanted status, is entity-ticking and its holder generation is
 * current; every other answer is the explicit handle of the reason and a retryability, and the
 * request never synchronously loads, joins or materializes the chunk. */
public final class NavigationChunkHandle {

    /** Why a navigation target has no direct answer; a handle is never a fake empty path. */
    public enum Reason {
        CHUNK_NOT_LOADED,
        STATUS_NOT_READY,
        ENTITY_TICKING_NOT_READY,
        HOLDER_STALE
    }

    /** One explicit navigation handle. */
    public record Handle(long worldIncarnation, long holderGen, long chunkPos, Reason reason,
                         boolean retryable) {

        public Handle {
            if (worldIncarnation < 0L || holderGen < 0L) {
                throw new IllegalArgumentException("a navigation handle cannot name a negative holder");
            }
            if (reason == null) {
                throw new IllegalArgumentException("a navigation handle names its reason");
            }
        }
    }

    /** The direct answer or the handle of one navigation target. */
    public record Decision(boolean direct, Handle handle) {

        public Decision {
            if (direct == (handle != null)) {
                throw new IllegalArgumentException("a navigation target is either direct or a handle");
            }
        }

        public boolean blocked() {
            return handle != null;
        }
    }

    /** Resolves one target chunk without loading, joining or waiting for a worker. */
    public static Decision resolve(long worldIncarnation, long holderGen, long chunkPos,
                                   long currentHolderGen, boolean loaded, boolean statusReady,
                                   boolean entityTicking) {
        if (holderGen < currentHolderGen) {
            return new Decision(false, new Handle(worldIncarnation, holderGen, chunkPos,
                Reason.HOLDER_STALE, false));
        }
        if (!loaded) {
            return new Decision(false, new Handle(worldIncarnation, holderGen, chunkPos,
                Reason.CHUNK_NOT_LOADED, true));
        }
        if (!statusReady) {
            return new Decision(false, new Handle(worldIncarnation, holderGen, chunkPos,
                Reason.STATUS_NOT_READY, true));
        }
        if (!entityTicking) {
            return new Decision(false, new Handle(worldIncarnation, holderGen, chunkPos,
                Reason.ENTITY_TICKING_NOT_READY, true));
        }
        return new Decision(true, null);
    }

    /** Whether one cross-boundary entity move may be answered directly by the target chunk. */
    public static boolean crossBoundaryDirect(long worldIncarnation, long holderGen, long targetChunk,
                                              long currentHolderGen, boolean loaded,
                                              boolean statusReady, boolean entityTicking) {
        return resolve(worldIncarnation, holderGen, targetChunk, currentHolderGen, loaded,
            statusReady, entityTicking).direct();
    }

    private NavigationChunkHandle() {
    }
}
