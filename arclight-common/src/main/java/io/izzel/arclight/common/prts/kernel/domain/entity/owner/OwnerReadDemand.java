/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

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

    private record Request(ServerChunkCache cache, int x, int z, ChunkStatus status,
                           boolean blocking, CompletableFuture<ChunkAccess> future) {
    }

    private static final ConcurrentLinkedQueue<Request> REQUESTS = new ConcurrentLinkedQueue<>();

    private OwnerReadDemand() {
    }

    /** Queues one read for a parked host and waits for its answer. Not registered as a wait point:
     * do not run it before the registration and continuation are separately authorized. */
    public static ChunkAccess await(ServerChunkCache cache, int x, int z, ChunkStatus status,
                                    boolean blocking) {
        Request request = new Request(cache, x, z, status, blocking, new CompletableFuture<>());
        REQUESTS.add(request);
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
