/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.entity.LivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The staged neighbour answer of one owner row: the host freezes the set before the wave, a worker
 * read node sorts it, and the seat pump binds it so the vanilla sensor skips its own scan. */
public final class NativeNeighborStage {

    private static final Map<String, List<LivingEntity>> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<List<LivingEntity>> CURRENT = new ThreadLocal<>();
    private static final AtomicLong STAGED = new AtomicLong();
    private static final AtomicLong CONSUMED = new AtomicLong();

    private NativeNeighborStage() {
    }

    public static List<LivingEntity> order(List<LivingEntity> neighbours, LivingEntity self) {
        if (neighbours == null || neighbours.isEmpty()) return List.of();
        neighbours.sort(Comparator.comparingDouble(self::distanceToSqr));
        return List.copyOf(neighbours);
    }

    public static void stage(String rowKey, List<LivingEntity> sorted) {
        READY.put(rowKey, sorted == null ? List.of() : sorted);
        STAGED.incrementAndGet();
    }

    /** Drops the staged answer of one row key; a bound answer is already taken. */
    public static void discard(String rowKey) {
        READY.remove(rowKey);
    }

    /** Drops every staged answer of one wave, matched by the wave token in the key. */
    public static void clearWave(long waveId) {
        String suffix = "|wave=" + waveId;
        READY.keySet().removeIf(key -> key.endsWith(suffix));
    }

    public static boolean has(String rowKey) {
        return READY.containsKey(rowKey);
    }

    public static void bind(String rowKey) {
        List<LivingEntity> staged = READY.remove(rowKey);
        if (staged != null) CURRENT.set(staged);
    }

    /** The staged list the reuse mixin consumed, or null when no stage was bound. */
    public static List<LivingEntity> take() {
        List<LivingEntity> staged = CURRENT.get();
        CURRENT.remove();
        if (staged != null) CONSUMED.incrementAndGet();
        return staged;
    }

    public static boolean bound() {
        return CURRENT.get() != null;
    }

    public static long staged() {
        return STAGED.get();
    }

    public static long consumed() {
        return CONSUMED.get();
    }

    public static void resetCounts() {
        STAGED.set(0);
        CONSUMED.set(0);
    }
}