/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The staged neighbour answer of one owner row. The host reads the raw neighbours before the wave,
 * a worker read node sorts them, and the seat-ordered write pump binds the sorted result for the
 * body so the vanilla sensor skips its own scan. One wave's entries are cleared at wave close. */
public final class NativeNeighborStage {

    private static final Map<String, List<LivingEntity>> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<List<LivingEntity>> CURRENT = new ThreadLocal<>();

    private NativeNeighborStage() {
    }

    /** Sorts one host-read neighbour list by the same distance key the vanilla sensor uses. */
    public static List<LivingEntity> order(List<LivingEntity> neighbours, LivingEntity self) {
        if (neighbours == null || neighbours.isEmpty()) {
            return List.of();
        }
        neighbours.sort(Comparator.comparingDouble(self::distanceToSqr));
        return List.copyOf(neighbours);
    }

    public static void stage(String rowKey, List<LivingEntity> sorted) {
        READY.put(rowKey, sorted == null ? List.of() : sorted);
    }

    public static List<LivingEntity> staged(String rowKey) {
        return READY.get(rowKey);
    }

    public static void clear(String rowKey) {
        READY.remove(rowKey);
    }

    public static void bind(String rowKey) {
        List<LivingEntity> staged = READY.get(rowKey);
        if (staged != null) {
            CURRENT.set(staged);
        }
    }

    public static List<LivingEntity> take() {
        List<LivingEntity> staged = CURRENT.get();
        CURRENT.remove();
        return staged;
    }

    public static boolean bound() {
        return CURRENT.get() != null;
    }
}