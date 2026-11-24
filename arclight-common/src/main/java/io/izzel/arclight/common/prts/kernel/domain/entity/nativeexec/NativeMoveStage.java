/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The staged collision answer of one owner row. A worker stages it, the Server-thread seat pump
 * binds it, and the original Entity.move call site takes it only when the live request still matches
 * the frozen one; otherwise the row falls back to the vanilla collide and counts as whole-line C. */
public final class NativeMoveStage {

    private static final Map<String, MoveCollisionResult> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<MoveCollisionResult> CURRENT = new ThreadLocal<>();
    private static final AtomicLong STAGED = new AtomicLong();
    private static final AtomicLong CONSUMED = new AtomicLong();

    private NativeMoveStage() {
    }

    /** Publishes one worker result under its row key. */
    public static void stage(String rowKey, MoveCollisionResult result) {
        READY.put(rowKey, result);
        STAGED.incrementAndGet();
    }

    /** Whether a result was staged for one row. */
    public static boolean has(String rowKey) {
        return READY.containsKey(rowKey);
    }

    /** Binds one staged result to the current seat; call on the Server thread only. */
    public static void bind(String rowKey) {
        MoveCollisionResult staged = READY.remove(rowKey);
        if (staged != null) {
            CURRENT.set(staged);
        }
    }

    /** Takes the staged answer when the live request still matches; a mismatch consumes nothing. */
    public static Vec3 take(Entity entity, Vec3 live) {
        MoveCollisionResult staged = CURRENT.get();
        if (staged == null || live == null) {
            return null;
        }
        Vec3 frozen = staged.request();
        if (frozen.x != live.x || frozen.y != live.y || frozen.z != live.z) {
            return null;
        }
        CURRENT.remove();
        CONSUMED.incrementAndGet();
        return staged.clipped();
    }

    /** Whether this seat has a bound staged answer. */
    public static boolean bound() {
        return CURRENT.get() != null;
    }

    /** Removes any bound answer at the end of a seat. */
    public static void unbind() {
        CURRENT.remove();
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