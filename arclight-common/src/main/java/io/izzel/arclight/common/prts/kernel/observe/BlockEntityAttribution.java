/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only block-entity attribution: one row per TickingBlockEntity tick plus the level segment total
 * for reconciliation. The row set is bounded and the source never cancels, delays or reorders a tick.
 * It is off by default and publishes no readout key. */
public final class BlockEntityAttribution {

    /** Test-only switch, default off; not a config key. */
    public static final String PROPERTY = "arclight.prts.beAttribution";
    private static final int ROW_LIMIT = 8192;
    private static final long RECONCILE_TOLERANCE_NANOS = 1_000_000L;

    /** One observed block-entity tick. */
    public record Row(String worldId, BlockPos pos, int chunkX, int chunkZ, String type,
                      boolean removed, long wallNanos, long cpuNanos, long tick) {
    }

    private static final ArrayDeque<Row> ROWS = new ArrayDeque<>();
    private static final Map<String, Long> PER_WORLD_WALL = new LinkedHashMap<>();
    private static final Map<String, Long> SEGMENT_WALL = new LinkedHashMap<>();
    private static long rowsSeen;
    private static long segmentTicks;
    private static long overflow;
    private static long reentrant;

    private BlockEntityAttribution() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static synchronized void noteTick(Object ticker, Object level, String worldId,
                                             long wallNanos, long cpuNanos) {
        if (!enabled() || !(ticker instanceof TickingBlockEntity blockEntity)) {
            return;
        }
        BlockPos pos = blockEntity.getPos();
        long tick = KernelModule.instance().tickIndex();
        ROWS.addLast(new Row(worldId, pos, pos == null ? 0 : pos.getX() >> 4,
            pos == null ? 0 : pos.getZ() >> 4, blockEntity.getType(), blockEntity.isRemoved(),
            Math.max(0L, wallNanos), cpuNanos, tick));
        rowsSeen++;
        PER_WORLD_WALL.merge(worldId, Math.max(0L, wallNanos), Long::sum);
        while (ROWS.size() > ROW_LIMIT) {
            ROWS.removeFirst();
            overflow++;
        }
    }

    /** The level's own block-entity segment total, used only to reconcile the row sum. */
    public static synchronized void noteSegment(String worldId, long wallNanos) {
        if (!enabled()) {
            return;
        }
        segmentTicks++;
        SEGMENT_WALL.merge(worldId, Math.max(0L, wallNanos), Long::sum);
    }

    public static synchronized long rowsSeen() {
        return rowsSeen;
    }

    public static synchronized long segmentTicks() {
        return segmentTicks;
    }

    public static synchronized long overflow() {
        return overflow;
    }

    public static synchronized long perWorldWall(String worldId) {
        return PER_WORLD_WALL.getOrDefault(worldId, 0L);
    }

    public static synchronized long segmentWall(String worldId) {
        return SEGMENT_WALL.getOrDefault(worldId, 0L);
    }

    /** True when the row sum of one world fits inside its segment total plus the read tolerance. */
    public static synchronized boolean reconciled(String worldId) {
        return perWorldWall(worldId) <= segmentWall(worldId) + RECONCILE_TOLERANCE_NANOS;
    }

    public static synchronized java.util.List<Row> rows() {
        return java.util.List.copyOf(ROWS);
    }

    public static synchronized void reset() {
        ROWS.clear();
        PER_WORLD_WALL.clear();
        SEGMENT_WALL.clear();
        rowsSeen = 0L;
        segmentTicks = 0L;
        overflow = 0L;
        reentrant = 0L;
    }
}
