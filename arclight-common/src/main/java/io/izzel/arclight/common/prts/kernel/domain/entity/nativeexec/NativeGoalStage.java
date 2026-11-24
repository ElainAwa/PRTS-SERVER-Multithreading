/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The prefiltered goal candidates of one owner row: the distance-and-type predicate of a behaviour
 * evaluated in the parallel read wave against the frozen neighbour set. The seat pump binds them so
 * the behaviour only runs the remaining line-of-sight check on a much smaller set. */
public final class NativeGoalStage {

    private static final Map<String, List<LivingEntity>> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<List<LivingEntity>> CURRENT = new ThreadLocal<>();
    private static final AtomicLong STAGED = new AtomicLong();
    private static final AtomicLong CONSUMED = new AtomicLong();

    private NativeGoalStage() {
    }

    public static void stage(String rowKey, List<LivingEntity> candidates) {
        READY.put(rowKey, candidates == null ? List.of() : candidates);
        STAGED.incrementAndGet();
    }

    public static boolean has(String rowKey) {
        return READY.containsKey(rowKey);
    }

    public static void bind(String rowKey) {
        List<LivingEntity> staged = READY.remove(rowKey);
        if (staged != null) CURRENT.set(staged);
    }

    public static List<LivingEntity> take() {
        List<LivingEntity> staged = CURRENT.get();
        CURRENT.remove();
        if (staged != null) CONSUMED.incrementAndGet();
        return staged;
    }

    public static boolean bound() {
        return CURRENT.get() != null;
    }

    public static long staged() { return STAGED.get(); }
    public static long consumed() { return CONSUMED.get(); }

    public static void resetCounts() { STAGED.set(0); CONSUMED.set(0); }
}