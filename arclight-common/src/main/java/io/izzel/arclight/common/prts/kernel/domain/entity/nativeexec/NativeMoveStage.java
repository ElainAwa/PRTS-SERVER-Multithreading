/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The staged collision answer and the frozen read of one owner row. The Server-thread seat pump
 * binds both; the real Entity.move call site uses the staged answer on a match, and otherwise
 * recomputes the same vanilla equation from the frozen read with the real argument. The live Level
 * is never consulted, and a request outside the frozen coverage is refused to the vanilla path. */
public final class NativeMoveStage {

    private static final Map<String, MoveCollisionResult> READY = new ConcurrentHashMap<>();
    private static final Map<String, NativeMoveRead> READS = new ConcurrentHashMap<>();
    private static final ThreadLocal<MoveCollisionResult> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<NativeMoveRead> CURRENT_READ = new ThreadLocal<>();
    private static final AtomicLong STAGED = new AtomicLong();
    private static final AtomicLong CONSUMED = new AtomicLong();
    private static final AtomicLong FROZEN_ANSWERED = new AtomicLong();
    private static final AtomicLong COVERAGE_REFUSED = new AtomicLong();

    private NativeMoveStage() {
    }

    /** Publishes one worker result under its row key. */
    public static void stage(String rowKey, MoveCollisionResult result) {
        READY.put(rowKey, result);
        STAGED.incrementAndGet();
    }

    /** Publishes the host-frozen read that the real move argument will be answered against. */
    public static void stageRead(String rowKey, NativeMoveRead read) {
        READS.put(rowKey, read);
    }

    /** Whether a result or a frozen read was staged for one row. */
    public static boolean has(String rowKey) {
        return READY.containsKey(rowKey) || READS.containsKey(rowKey);
    }

    /** Binds one staged result and its frozen read to the current seat (Server thread only). */
    public static void bind(String rowKey) {
        MoveCollisionResult staged = READY.remove(rowKey);
        if (staged != null) {
            CURRENT.set(staged);
        }
        NativeMoveRead read = READS.remove(rowKey);
        if (read != null) {
            CURRENT_READ.set(read);
        }
    }

    /** Takes the staged answer on a match; otherwise recomputes from the frozen read with the real
     * argument. A request outside the frozen coverage consumes nothing and returns null so the
     * caller keeps the whole-row C path. An unused staged answer is waste, counted by staged minus
     * consumed; no new reading key is introduced. */
    public static Vec3 take(Entity entity, Vec3 live) {
        if (live == null) {
            return null;
        }
        MoveCollisionResult staged = CURRENT.get();
        if (staged != null) {
            Vec3 frozen = staged.request();
            if (frozen.x == live.x && frozen.y == live.y && frozen.z == live.z) {
                CURRENT.remove();
                CURRENT_READ.remove();
                CONSUMED.incrementAndGet();
                return staged.clipped();
            }
        }
        NativeMoveRead read = CURRENT_READ.get();
        if (read != null && read.covers(live)) {
            CURRENT.remove();
            CURRENT_READ.remove();
            FROZEN_ANSWERED.incrementAndGet();
            return NativeMoveCompute.computeWith(entity, read, live).clipped();
        }
        if (read != null) {
            COVERAGE_REFUSED.incrementAndGet();
        }
        return null;
    }

    /** Test-only observation of frozen answers. */
    public static long frozenAnsweredForTest() {
        return FROZEN_ANSWERED.get();
    }

    /** Test-only observation of coverage refusals. */
    public static long coverageRefusedForTest() {
        return COVERAGE_REFUSED.get();
    }

    /** Whether this seat has a bound staged answer or frozen read. */
    public static boolean bound() {
        return CURRENT.get() != null || CURRENT_READ.get() != null;
    }

    /** Whether this seat carries a frozen read; the real call site uses it before vanilla collide. */
    public static boolean hasRead() {
        return CURRENT_READ.get() != null;
    }

    /** Removes any bound answer and read at the end of a seat. */
    public static void unbind() {
        CURRENT.remove();
        CURRENT_READ.remove();
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
        FROZEN_ANSWERED.set(0);
        COVERAGE_REFUSED.set(0);
    }
}
