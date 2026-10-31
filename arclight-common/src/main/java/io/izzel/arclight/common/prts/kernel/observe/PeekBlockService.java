/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** A read-only block peek that never loads, joins, tickets or generates a chunk. Only a registered
 * level whose chunk is already visible can answer with a live state; otherwise the caller gets an
 * immutable UNKNOWN/DEFAULT result and one bounded demand entry. No readout key is published. */
public final class PeekBlockService {

    /** How the answer was obtained; recorded with every result. */
    public enum Status {
        LOADED,
        UNKNOWN,
        DEFAULT,
        NO_WORLD
    }

    /** Immutable answer; the block object is only present for LOADED. */
    public record Result(String worldId, BlockPos pos, Object block, Status status, String source) {
    }

    private static final int DEMAND_LIMIT = 1024;
    private static final Map<String, Integer> DEMAND = new LinkedHashMap<>();
    private static long demandCount;
    private static long demandOverflow;

    private PeekBlockService() {
    }

    /** Pure decision used by the live adapter and the offline tests. The view supplier is called
     * only when the chunk is already loaded. */
    public static Result decide(boolean registered, boolean loaded, Supplier<Object> view,
                                Object fallback, String worldId, BlockPos pos) {
        if (!registered) {
            noteDemand(worldId, pos, "no-world");
            return new Result(worldId, pos, fallback, Status.NO_WORLD, "no-world");
        }
        if (!loaded) {
            noteDemand(worldId, pos, "chunk-absent");
            return fallback == null
                ? new Result(worldId, pos, null, Status.UNKNOWN, "chunk-absent")
                : new Result(worldId, pos, fallback, Status.DEFAULT, "chunk-absent");
        }
        Object block = view.get();
        return new Result(worldId, pos, block, Status.LOADED, "chunk-now");
    }

    /** Live adapter: getChunkNow only; a missing chunk is an answer, never a load. */
    public static Result peek(ServerLevel level, BlockPos pos, Object fallback) {
        if (level == null || pos == null) {
            return new Result("", pos, fallback, Status.NO_WORLD, "no-level");
        }
        String worldId = level.dimension().location().toString();
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return decide(true, chunk != null,
            () -> chunk.getBlockState(pos), fallback, worldId, pos);
    }

    /** One bounded demand entry; a repeated coordinate adds to its count instead of a new row. */
    private static synchronized void noteDemand(String worldId, BlockPos pos, String source) {
        demandCount++;
        String key = worldId + "|" + (pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ())
            + "|" + source;
        if (DEMAND.containsKey(key)) {
            DEMAND.merge(key, 1, Integer::sum);
            return;
        }
        if (DEMAND.size() >= DEMAND_LIMIT) {
            demandOverflow++;
            return;
        }
        DEMAND.put(key, 1);
    }

    public static synchronized long demandCount() {
        return demandCount;
    }

    public static synchronized long demandOverflow() {
        return demandOverflow;
    }

    public static synchronized java.util.List<String> demandRows() {
        return DEMAND.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
            .toList();
    }

    public static synchronized void reset() {
        DEMAND.clear();
        demandCount = 0L;
        demandOverflow = 0L;
    }

    /** Convenience for the command face: the block id or a stable marker, never a chunk load. */
    public static String describe(Result result) {
        if (result == null) {
            return "status=unknown source=null";
        }
        String value = result.block() instanceof BlockState state
            ? state.getBlock().toString() : String.valueOf(result.block());
        return "status=" + result.status().name().toLowerCase(java.util.Locale.ROOT)
            + " source=" + result.source() + " block=" + value;
    }
}
