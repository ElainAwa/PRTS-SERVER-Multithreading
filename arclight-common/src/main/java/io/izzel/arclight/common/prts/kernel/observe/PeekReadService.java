/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.bridge.core.server.level.DistanceManagerBridge;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Frozen read-only peek receipt: each explicit peek records the before/after loaded state, the
 * bounded demand/loading/materialization counters and the real chunk's active tickets and persistent
 * forceload intent. It never calls getChunk(load=true), join or ticket APIs. Unavailable sources are
 * UNKNOWN, never zero; a known-empty set stays a known empty set. */
public final class PeekReadService {

    /** One immutable read receipt; every source that could not be observed is explicitly UNKNOWN. */
    public record ReadReceipt(long readId, long epoch, String worldId, long chunkPos, long sequence,
                              long timeNanos, PeekBlockService.Status status, String source,
                              long demand, boolean loadedBefore, boolean loadedAfter, String holder,
                              List<String> tickets, boolean ticketsKnown, long loadingCalls,
                              long newTickets, String materializationSource, boolean eventLoss,
                              boolean forceloadIntent, String ticketSource) {
    }

    /** One read-only ticket view: active runtime set, persistent intent, and their sources. */
    record TicketView(List<String> active, boolean known, boolean forceloadIntent, String source) {
    }

    private static final int RING_LIMIT = 256;
    private static final ArrayDeque<ReadReceipt> RING = new ArrayDeque<>();
    private static final AtomicLong READ_IDS = new AtomicLong();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static long sequence;
    private static long overflow;

    private PeekReadService() {
    }

    public static synchronized long epoch() {
        return EPOCH.get();
    }

    public static synchronized long advanceEpoch() {
        return EPOCH.incrementAndGet();
    }

    /** One explicit read: freeze the receipt and keep only a bounded history. */
    public static ReadReceipt read(ServerLevel level, BlockPos pos, Object fallback) {
        long readId = READ_IDS.incrementAndGet();
        long epoch = EPOCH.get();
        long started = System.nanoTime();
        String worldId = level == null ? "" : level.dimension().location().toString();
        long chunkPos = pos == null ? 0L : ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        long demandBefore = PrtsChunkDemand.demandCalls();
        long materializedBefore = PrtsChunkMaterialization.materializedCount();
        boolean loadedBefore = loaded(level, pos);
        TicketView ticketsBefore = tickets(level, pos);
        long managedReadId = MaterializedUnloadedReadings.begin(worldId, chunkPos, "peek");
        PeekBlockService.Result result = PeekBlockService.peek(level, pos, fallback);
        MaterializedUnloadedReadings.end(managedReadId,
            result.status() == PeekBlockService.Status.NO_WORLD
                ? MaterializedUnloadedReadings.Outcome.NO_WORLD
                : result.status() == PeekBlockService.Status.LOADED
                    ? MaterializedUnloadedReadings.Outcome.LOADED_SNAPSHOT
                    : MaterializedUnloadedReadings.Outcome.COMPLETE);
        boolean loadedAfter = loaded(level, pos);
        TicketView ticketsAfter = tickets(level, pos);
        boolean ticketsKnown = ticketsBefore.known() && ticketsAfter.known();
        long demand = PrtsChunkDemand.demandCalls() - demandBefore;
        long materialized = PrtsChunkMaterialization.materializedCount() - materializedBefore;
        long newTickets = ticketsKnown ? setDifference(ticketsAfter.active(), ticketsBefore.active()) : -1L;
        String materializationSource = materialized > 0L ? PrtsChunkMaterialization.SOURCE
            : demand > 0L ? "chunk.demand" : "none";
        boolean ticketChanged = ticketsKnown
            && !ticketsAfter.active().equals(ticketsBefore.active());
        boolean eventLoss = ticketChanged && demand == 0L && materialized == 0L;
        String ticketSource = ticketsBefore.source().equals(ticketsAfter.source())
            ? ticketsAfter.source() : "source-changed";
        ReadReceipt receipt = new ReadReceipt(readId, epoch, worldId, chunkPos, sequence++,
            System.nanoTime() - started, result.status(), result.source(), demand, loadedBefore,
            loadedAfter, result.block() == null ? "none" : "peek-result",
            ticketsKnown ? ticketsAfter.active() : List.of("UNKNOWN"), ticketsKnown, demand,
            newTickets, materializationSource, eventLoss, ticketsAfter.forceloadIntent(), ticketSource);
        append(receipt);
        return receipt;
    }

    /** Pure receipt builder for offline calibration tests. */
    public static ReadReceipt build(long readId, long epoch, String worldId, long chunkPos,
                                    long sequence, long timeNanos, PeekBlockService.Status status,
                                    String source, long demand, boolean loadedBefore,
                                    boolean loadedAfter, String holder, List<String> tickets,
                                    boolean ticketsKnown, long loadingCalls, long newTickets,
                                    String materializationSource, boolean eventLoss,
                                    boolean forceloadIntent, String ticketSource) {
        return new ReadReceipt(readId, epoch, worldId, chunkPos, sequence, timeNanos, status,
            source, demand, loadedBefore, loadedAfter, holder, List.copyOf(tickets), ticketsKnown,
            loadingCalls, newTickets, materializationSource, eventLoss, forceloadIntent, ticketSource);
    }

    private static void append(ReadReceipt receipt) {
        synchronized (PeekReadService.class) {
            RING.addLast(receipt);
            while (RING.size() > RING_LIMIT) {
                RING.removeFirst();
                overflow++;
            }
        }
    }

    public static synchronized List<ReadReceipt> ring() {
        return List.copyOf(RING);
    }

    public static synchronized long overflow() {
        return overflow;
    }

    public static synchronized void reset() {
        RING.clear();
        READ_IDS.set(0L);
        EPOCH.set(0L);
        sequence = 0L;
        overflow = 0L;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level != null && pos != null
            && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** Owner-thread only: the bridge copies the active set, the forced set is read separately. */
    private static TicketView tickets(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return new TicketView(List.of("UNKNOWN"), false, false, "source-unavailable");
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return new TicketView(List.of("UNKNOWN"), false, false, "thread-unavailable");
        }
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<String> active;
        boolean activeKnown;
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap map = cache.chunkMap;
            DistanceManager distance = map.getDistanceManager();
            active = ((DistanceManagerBridge) distance).bridge$ticketSnapshot(chunk);
            activeKnown = true;
        } catch (Throwable unknown) {
            active = List.of("UNKNOWN");
            activeKnown = false;
        }
        boolean forcedKnown;
        boolean forceloadIntent = false;
        try {
            forceloadIntent = level.getForcedChunks().contains(chunk);
            forcedKnown = true;
        } catch (Throwable unknown) {
            forcedKnown = false;
        }
        return ticketDecision(true, loaded(level, pos), active, activeKnown, forcedKnown, forceloadIntent);
    }

    /** Pure ticket-view decision: known-empty stays known; only an unreadable source is UNKNOWN. */
    static TicketView ticketDecision(boolean ownerThread, boolean loaded, List<String> active,
                                     boolean activeKnown, boolean forcedKnown,
                                     boolean forceloadIntent) {
        if (!ownerThread) {
            return new TicketView(List.of("UNKNOWN"), false, forceloadIntent, "thread-unavailable");
        }
        if (!activeKnown || !forcedKnown) {
            return new TicketView(List.of("UNKNOWN"), false, forceloadIntent, "source-unavailable");
        }
        List<String> names = List.copyOf(active);
        String source = loaded && names.isEmpty() ? "known-empty" : "active";
        return new TicketView(names, true, forceloadIntent, source);
    }

    /** Package-private for the direct new-ticket difference regression; pure set operation. */
    static long setDifference(List<String> after, List<String> before) {
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(new LinkedHashSet<>(before));
        return added.size();
    }
}
