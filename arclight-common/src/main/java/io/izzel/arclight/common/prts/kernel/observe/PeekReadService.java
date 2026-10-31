/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Frozen read-only peek receipt: each explicit peek records the before/after loaded state, the
 * bounded demand/loading/materialization counters and the real chunk's ticket set. It never calls
 * getChunk(load=true), join or ticket APIs. Unavailable sources are UNKNOWN, never zero. */
public final class PeekReadService {

    /** One immutable read receipt; every source that could not be observed is explicitly UNKNOWN. */
    public record ReadReceipt(long readId, long epoch, String worldId, long chunkPos, long sequence,
                              long timeNanos, PeekBlockService.Status status, String source,
                              long demand, boolean loadedBefore, boolean loadedAfter, String holder,
                              List<String> tickets, boolean ticketsKnown, long loadingCalls,
                              long newTickets, String materializationSource, boolean eventLoss) {
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
        long chunkPos = pos == null ? 0L : ((long) (pos.getX() >> 4) << 32)
            ^ ((pos.getZ() >> 4) & 0xffffffffL);
        long demandBefore = PrtsChunkDemand.demandCalls();
        long materializedBefore = PrtsChunkMaterialization.materializedCount();
        boolean loadedBefore = loaded(level, pos);
        List<String> ticketsBefore = tickets(level, pos);
        boolean ticketsKnown = ticketsBefore != null;
        PeekBlockService.Result result = PeekBlockService.peek(level, pos, fallback);
        boolean loadedAfter = loaded(level, pos);
        List<String> ticketsAfter = tickets(level, pos);
        long demand = PrtsChunkDemand.demandCalls() - demandBefore;
        long materialized = PrtsChunkMaterialization.materializedCount() - materializedBefore;
        long newTickets = ticketsAfter == null || ticketsBefore == null ? -1L
            : Math.max(0L, ticketsAfter.size() - ticketsBefore.size());
        String materializationSource = materialized > 0L ? PrtsChunkMaterialization.SOURCE
            : demand > 0L ? "chunk.demand" : "none";
        boolean ticketChanged = ticketsAfter != null && ticketsBefore != null
            && !ticketsAfter.equals(ticketsBefore);
        boolean eventLoss = ticketChanged && demand == 0L && materialized == 0L;
        ReadReceipt receipt = new ReadReceipt(readId, epoch, worldId, chunkPos, sequence++,
            System.nanoTime() - started, result.status(), result.source(), demand, loadedBefore,
            loadedAfter, result.block() == null ? "none" : "peek-result",
            ticketsKnown ? List.copyOf(ticketsAfter) : List.of("UNKNOWN"), ticketsKnown, demand,
            newTickets, materializationSource, eventLoss);
        append(receipt);
        return receipt;
    }

    /** Pure receipt builder for offline calibration tests. */
    public static ReadReceipt build(long readId, long epoch, String worldId, long chunkPos,
                                    long sequence, long timeNanos, PeekBlockService.Status status,
                                    String source, long demand, boolean loadedBefore,
                                    boolean loadedAfter, String holder, List<String> tickets,
                                    boolean ticketsKnown, long loadingCalls, long newTickets,
                                    String materializationSource, boolean eventLoss) {
        return new ReadReceipt(readId, epoch, worldId, chunkPos, sequence, timeNanos, status,
            source, demand, loadedBefore, loadedAfter, holder, List.copyOf(tickets), ticketsKnown,
            loadingCalls, newTickets, materializationSource, eventLoss);
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

    /** Reads the real DistanceManager ticket set by reflection; null means the source is UNKNOWN. */
    private static List<String> tickets(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        try {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap map = cache.chunkMap;
            DistanceManager distance = map.getDistanceManager();
            Field field = DistanceManager.class.getDeclaredField("tickets");
            field.setAccessible(true);
            Object raw = field.get(distance);
            if (raw == null) {
                return List.of();
            }
            long chunk = ((long) (pos.getX() >> 4) << 32) ^ ((pos.getZ() >> 4) & 0xffffffffL);
            Method get = raw.getClass().getMethod("get", long.class);
            Object set = get.invoke(raw, chunk);
            if (set == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (Object ticket : (Iterable<?>) set) {
                names.add(String.valueOf(ticket));
            }
            return names;
        } catch (Throwable unknown) {
            return null;
        }
    }
}
