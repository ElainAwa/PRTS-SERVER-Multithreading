/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Dormant host-served bridge for chunk reads an owner worker cannot serve itself: the worker queues
 * the request and parks on the future while the host runs the read. It is not a registered wait
 * point and must not run before one exists; until then the segment admission keeps demand-bearing
 * segments on the host, so no production path reaches this bridge. No read is cancelled or reordered,
 * and a read the host cannot serve fails the future with the original throwable. */
public final class OwnerReadDemand {

    /** One demand with the identity a later continuation checks; no generation rejection here yet. */
    record Request(String worldId, long holderGeneration, String consumerSegment, long logicalTick,
                   int position, ServerChunkCache cache, int x, int z, ChunkStatus status,
                   boolean blocking, CompletableFuture<ChunkAccess> future) {
    }

    private static final ConcurrentLinkedQueue<Request> REQUESTS = new ConcurrentLinkedQueue<>();

    private OwnerReadDemand() {
    }

    /** Queues one read for a parked host and waits for its answer. Not registered as a wait point:
     * do not run it before the registration and continuation are separately authorized. */
    public static ChunkAccess await(ServerChunkCache cache, int x, int z, ChunkStatus status,
                                    boolean blocking) {
        PrtsWaitSites.begin(PrtsWaitSites.OWNER_READ_DEMAND);
        try {
            return await(cache, x, z, status, blocking, "", -1L, "", -1L, -1);
        } finally {
            PrtsWaitSites.end(PrtsWaitSites.OWNER_READ_DEMAND);
        }
    }

    /** The identity-carrying entry used by the owner side; the old short form delegates here. */
    static ChunkAccess await(ServerChunkCache cache, int x, int z, ChunkStatus status,
                             boolean blocking, String worldId, long holderGeneration,
                             String consumerSegment, long logicalTick, int position) {
        // A read node declared itself demand-free: the host is not pumping inside the wave, so a
        // demand would wait forever. The fence records the demand and refuses the node instead.
        io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.fence(
            io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeReadGuard.Refusal
                .CHUNK_DEMAND);
        Request request = enqueue(cache, x, z, status, blocking, worldId, holderGeneration,
            consumerSegment, logicalTick, position);
        try {
            return request.future().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("owner chunk demand failed", cause);
        }
    }

    /** Enqueues one request without waiting; the identity fields travel with it. */
    static Request enqueue(ServerChunkCache cache, int x, int z, ChunkStatus status,
                           boolean blocking, String worldId, long holderGeneration,
                           String consumerSegment, long logicalTick, int position) {
        Request request = new Request(worldId == null ? "" : worldId, holderGeneration,
            consumerSegment == null ? "" : consumerSegment, logicalTick, position, cache, x, z,
            status, blocking, new CompletableFuture<>());
        REQUESTS.add(request);
        return request;
    }

    /** Serves queued reads on the calling host thread; the host has to be parked for this to run. */
    public static boolean service() {
        boolean served = false;
        Request request;
        while ((request = REQUESTS.poll()) != null) {
            served = true;
            try {
                request.future().complete(request.cache().getChunk(request.x(), request.z(),
                    request.status(), request.blocking()));
            } catch (Throwable failure) {
                request.future().completeExceptionally(failure);
            }
        }
        return served;
    }

    public static boolean pending() {
        return !REQUESTS.isEmpty();
    }
}
