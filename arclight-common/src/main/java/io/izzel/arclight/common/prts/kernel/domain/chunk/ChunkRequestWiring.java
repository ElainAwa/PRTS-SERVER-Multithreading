/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The production wiring of the chunk request contract: the demand seam registers one identity per
 * production request (the subscriber is the call/task token, the world incarnation is the kernel's
 * epoch, the holder generation is the explicit unavailable value when the call cannot name one and
 * the deadline stays N/A), a satisfied future or synchronous read delivers every subscriber of the
 * merged production unit on its own target status, and a materialized chunk advances the completion
 * layers the event really reports. The wiring is read-only: it changes no load, generation or send
 * result and order, waits for nothing and reads no world.
 */
public final class ChunkRequestWiring {

    /** The bound of the in-flight call-token map; an older token is dropped and counted. */
    public static final int TOKEN_CAP = 4096;
    /** The bound of the completion-state map; the oldest chunk state is dropped and counted. */
    public static final int COMPLETION_CAP = 2048;
    /** The bound of the bounded wiring of one process; the wiring itself keeps no unbounded map. */
    public static final int DEFAULT_CAP = ChunkSubscriberLedger.DEFAULT_CAP;

    /** The holder generation a call cannot name: an explicit unavailable value, never a fake one. */
    public static final long HOLDER_GEN_UNAVAILABLE = 0L;
    /** The deadline a call cannot name: reported as N/A by the identity, never a fake deadline. */
    public static final long DEADLINE_UNAVAILABLE = 0L;

    private record PendingRequest(long chunkPos, String worldId, String status, long subscriberSeq) {
    }

    private static final ChunkSubscriberLedger LEDGER = new ChunkSubscriberLedger();
    private static final Map<Long, ChunkCompletionState> COMPLETIONS = new ConcurrentHashMap<>();
    private static final java.util.LinkedHashMap<Long, PendingRequest> TOKENS =
        new java.util.LinkedHashMap<>();
    private static final ThreadLocal<PendingRequest> SYNC = new ThreadLocal<>();
    private static long tokensEvicted;
    private static long completionsEvicted;
    private static long syncLeaks;
    private static final java.util.concurrent.atomic.AtomicLong SYNC_SEQ =
        new java.util.concurrent.atomic.AtomicLong();

    private ChunkRequestWiring() {
    }

    public static ChunkSubscriberLedger ledger() {
        return LEDGER;
    }

    /** The completion state of one chunk; the completed-state map keeps the bounded LRU. */
    public static ChunkCompletionState completion(long chunkPos) {
        synchronized (COMPLETIONS) {
            ChunkCompletionState existing = COMPLETIONS.get(chunkPos);
            if (existing != null) {
                return existing;
            }
            if (COMPLETIONS.size() >= COMPLETION_CAP) {
                java.util.Iterator<Long> eldest = COMPLETIONS.keySet().iterator();
                if (eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                    completionsEvicted++;
                }
            }
            ChunkCompletionState created = new ChunkCompletionState();
            COMPLETIONS.put(chunkPos, created);
            return created;
        }
    }

    /** One future-returning demand: registers its subscriber identity under its call token. */
    public static void demandOpened(String worldId, int chunkX, int chunkZ, String status,
                                    long token, Object taskToken) {
        if (token <= 0L) {
            return;
        }
        PendingRequest request = new PendingRequest(chunkPos(chunkX, chunkZ), worldId, status,
            token);
        synchronized (TOKENS) {
            if (!TOKENS.containsKey(token) && TOKENS.size() >= TOKEN_CAP) {
                java.util.Iterator<Long> eldest = TOKENS.keySet().iterator();
                if (eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                    tokensEvicted++;
                }
            }
            PendingRequest previous = TOKENS.put(token, request);
            if (previous != null) {
                tokensEvicted++;
            }
        }
        LEDGER.register(identity(worldId, chunkX, chunkZ, status, token, taskToken));
    }

    /** One synchronous demand: registers its identity and remembers it for the matching finish. */
    public static void syncOpened(String worldId, int chunkX, int chunkZ, String status,
                                  long subscriberSeq) {
        if (subscriberSeq <= 0L) {
            subscriberSeq = SYNC_SEQ.incrementAndGet();
        }
        PendingRequest leaked = SYNC.get();
        if (leaked != null) {
            syncLeaks++;
        }
        PendingRequest request = new PendingRequest(chunkPos(chunkX, chunkZ), worldId, status,
            subscriberSeq);
        SYNC.set(request);
        LEDGER.register(identity(worldId, chunkX, chunkZ, status, subscriberSeq, null));
    }

    /** One finished future completes every subscriber the achieved status satisfies; the call
     * token leaves the map whether the future succeeded or not. */
    public static void futureFinished(String worldId, String status, long token,
                                      boolean satisfied) {
        PendingRequest request;
        synchronized (TOKENS) {
            request = TOKENS.remove(token);
        }
        if (!satisfied || request == null) {
            return;
        }
        complete(worldId, request.chunkPos(), status);
    }

    /** One failed registration drops its token; a token that never completes cannot leak. */
    public static void registrationFailed(long token) {
        synchronized (TOKENS) {
            if (TOKENS.remove(token) != null) {
                tokensEvicted++;
            }
        }
    }

    /** One synchronous ask that answered completes its own subscriber only. */
    public static void syncFinished(boolean satisfied) {
        PendingRequest request = SYNC.get();
        SYNC.remove();
        if (!satisfied || request == null) {
            return;
        }
        complete(request.worldId(), request.chunkPos(), request.status());
    }

    /** One materialized chunk advances only the layers the materialization really reports. */
    public static void materialized(String worldId, long chunkPos, String status) {
        ChunkCompletionState state = completion(chunkPos);
        state.mark(HOLDER_GEN_UNAVAILABLE, ChunkCompletionLayer.LOADED);
        if (PrtsChunkMaterialization.STATUS_FULL.equals(status)) {
            state.mark(HOLDER_GEN_UNAVAILABLE, ChunkCompletionLayer.TARGET_STATUS_READY);
        }
        // The other six layers have no production signal here; they stay NOT_OBSERVED.
        complete(worldId, chunkPos, status);
    }

    /** Drops every registration and completion state; test and evidence code only. */
    public static void reset() {
        LEDGER.reset();
        synchronized (TOKENS) {
            TOKENS.clear();
        }
        synchronized (COMPLETIONS) {
            COMPLETIONS.clear();
        }
        SYNC.remove();
        tokensEvicted = 0;
        completionsEvicted = 0;
        syncLeaks = 0;
    }

    public static int tokenCount() {
        synchronized (TOKENS) {
            return TOKENS.size();
        }
    }

    public static int completionCount() {
        synchronized (COMPLETIONS) {
            return COMPLETIONS.size();
        }
    }

    public static long tokensEvicted() {
        return tokensEvicted;
    }

    public static long completionsEvicted() {
        return completionsEvicted;
    }

    public static long syncLeaks() {
        return syncLeaks;
    }

    /** Cancels only the named subscriber; every other subscriber stays pending. */
    public static int cancelSubscriber(String subscriberId) {
        return LEDGER.cancel(subscriberId);
    }

    /** The subscriber id of one production call: its token, or "N/A" when the call names none. */
    public static String subscriberId(long token, Object taskToken) {
        if (token > 0L) {
            return "chunk:" + token;
        }
        return taskToken == null ? "chunk:N/A" : "task:" + System.identityHashCode(taskToken);
    }

    private static void complete(String worldId, long chunkPos, String achievedStatus) {
        LEDGER.complete(identity(worldId, chunkX(chunkPos), chunkZ(chunkPos), achievedStatus,
            DEADLINE_UNAVAILABLE, null), achievedStatus);
    }

    private static ChunkRequestIdentity identity(String worldId, int chunkX, int chunkZ,
                                                 String status, long subscriberSeq,
                                                 Object taskToken) {
        return new ChunkRequestIdentity(worldIncarnation(worldId), chunkX, chunkZ,
            HOLDER_GEN_UNAVAILABLE, status, subscriberId(subscriberSeq, taskToken),
            DEADLINE_UNAVAILABLE);
    }

    private static long worldIncarnation(String worldId) {
        try {
            return io.izzel.arclight.common.prts.kernel.KernelModule.instance().guard()
                .worldEpochs().epochOf(worldId);
        } catch (Throwable unavailable) {
            return 0L;
        }
    }

    private static long chunkPos(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(long chunkPos) {
        return (int) (chunkPos >> 32);
    }

    private static int chunkZ(long chunkPos) {
        return (int) chunkPos;
    }
}
