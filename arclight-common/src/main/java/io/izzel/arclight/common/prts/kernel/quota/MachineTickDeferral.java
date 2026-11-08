/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The bounded MACHINE execution boundary: while the machine quota window is over, the root neighbour
 * updates of one tick are held in a bounded FIFO of the level that issued them and replayed once, in
 * their original order, at that level tick's own RETURN (same tick, same thread); the module-level
 * drain is only the fallback for a call issued after its level returned. Above the bound the queued
 * calls are flushed in place before the new one runs, so no call is dropped and the order is kept;
 * each call is executed exactly once with its original arguments and nothing runs on another thread.
 * A plan a missed safe point leaves behind is never cleared silently: the same world lifecycle is
 * replayed late with an explicit receipt, a foreign lifecycle is retired with an explicit
 * INVALIDATED receipt and its dropped count. The engine only moves the execution point inside the
 * tick; it does not claim that the world outcome is bit-identical to the un-deferred run.
 *
 * <p>State is owned by the server thread; every entry point is synchronized. A replay is marked on
 * the thread so a neighbour update issued while replaying never enters the queue again.
 */
public final class MachineTickDeferral {

    /** Most deferred calls one level tick may hold; above it the queue is flushed in place. */
    public static final int DEFERRED_LIMIT = 64;

    /** Most receipts kept; the older ones fall out so the evidence stays bounded. */
    public static final int RECEIPT_LIMIT = 64;

    /** How one armed tick ended at the safe point. */
    public enum Result {
        DEFERRED,
        INVALIDATED
    }

    /** One armed tick and what happened to the calls it deferred. */
    public record Receipt(long token, String worldId, long tickIndex, long worldEpoch,
                          int deferredTicks, int flushedTicks, int drainedTicks, int droppedTicks,
                          Result result) {
    }

    /** Replays one deferred root neighbour update through the same public level method. The block
     * argument is the opaque block the production call carried; the engine never inspects it. */
    @FunctionalInterface
    public interface Replay {
        void update(Object level, BlockPos pos, Object block, Direction facing);
    }

    private record Deferred(Object level, BlockPos pos, Object block, Direction facing) {
    }

    private static final class Plan {

        private final String worldId;
        private final long tickIndex;
        private final long worldEpoch;
        private final ArrayDeque<Deferred> queue = new ArrayDeque<>();
        private int deferredTicks;
        private int flushedTicks;
        private long token;

        private Plan(String worldId, long tickIndex, long worldEpoch) {
            this.worldId = worldId;
            this.tickIndex = tickIndex;
            this.worldEpoch = worldEpoch;
        }
    }

    private static final Map<Object, Plan> OPEN = new LinkedHashMap<>();
    private static final ArrayDeque<Receipt> RECEIPTS = new ArrayDeque<>();
    private static final ThreadLocal<Integer> REPLAY_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static long nextToken = 1L;
    private static long totalDeferred;
    private static long totalFlushed;
    private static long totalDrained;
    private static long totalDropped;

    private MachineTickDeferral() {
    }

    /** True while one deferred call (and everything it cascaded) is being replayed. */
    public static boolean inReplay() {
        return REPLAY_DEPTH.get() > 0;
    }

    /** True while one level still holds deferred calls of the current tick. */
    public static synchronized boolean hasWaiting() {
        return !OPEN.isEmpty();
    }

    /** True while that level still holds deferred calls of its current tick. */
    public static synchronized boolean hasWaiting(Object level) {
        return level != null && OPEN.containsKey(level);
    }

    /** Holds one root neighbour update of this tick when the action is armed; false runs it inline. */
    public static synchronized boolean defer(Object level, String worldId, long tickIndex,
                                             long worldEpoch, BlockPos pos, Object block,
                                             Direction facing, boolean armed, Replay replay) {
        if (!armed || inReplay() || level == null || worldId == null || pos == null
            || block == null || replay == null) {
            return false;
        }
        Plan plan = OPEN.get(level);
        if (plan == null) {
            plan = new Plan(worldId, tickIndex, worldEpoch);
            OPEN.put(level, plan);
        } else if (plan.tickIndex != tickIndex || plan.worldEpoch != worldEpoch) {
            // A safe point was missed. The old plan is never cleared silently: a plan of the same
            // world lifecycle is replayed now (late, but once and in order, with an explicit
            // receipt); a foreign world lifecycle cannot be replayed into the new generation and is
            // retired with an explicit INVALIDATED receipt and its dropped count.
            if (plan.worldEpoch == worldEpoch) {
                replayLate(plan, replay);
            } else {
                retire(plan);
            }
            plan = new Plan(worldId, tickIndex, worldEpoch);
            OPEN.put(level, plan);
        }
        if (plan.queue.size() >= DEFERRED_LIMIT) {
            flush(plan, replay);
        }
        plan.queue.addLast(new Deferred(level, pos, block, facing));
        plan.deferredTicks++;
        totalDeferred++;
        return true;
    }

    /** Replays one level's waiting plan at that level tick's own RETURN (same tick, same thread). */
    public static synchronized int drainScope(Object level, ToLongFunction<String> worldEpoch,
                                              Replay replay) {
        Plan plan = level == null ? null : OPEN.remove(level);
        return plan == null ? 0 : drainPlan(plan, worldEpoch, replay);
    }

    /** Drops every plan of one world at its unload; a deferred call of an unloaded level is not
     * replayed and is booked as dropped. */
    public static synchronized int forgetWorld(String worldId) {
        int dropped = 0;
        if (worldId == null) {
            return 0;
        }
        Iterator<Map.Entry<Object, Plan>> plans = OPEN.entrySet().iterator();
        while (plans.hasNext()) {
            Plan plan = plans.next().getValue();
            if (worldId.equals(plan.worldId)) {
                dropped += plan.queue.size();
                plans.remove();
            }
        }
        totalDropped += dropped;
        return dropped;
    }

    /** Fallback safe point: replays every waiting plan a level tick RETURN did not drain. */
    public static synchronized int drain(ToLongFunction<String> worldEpoch, Replay replay) {
        int drained = 0;
        for (Object level : new ArrayList<>(OPEN.keySet())) {
            Plan plan = OPEN.remove(level);
            if (plan != null) {
                drained += drainPlan(plan, worldEpoch, replay);
            }
        }
        return drained;
    }

    private static int drainPlan(Plan plan, ToLongFunction<String> worldEpoch, Replay replay) {
        boolean invalid = worldEpoch == null || replay == null
            || worldEpoch.applyAsLong(plan.worldId) != plan.worldEpoch;
        int ran = 0;
        int dropped = 0;
        for (Deferred record : plan.queue) {
            if (invalid) {
                dropped++;
                continue;
            }
            replay(record, replay);
            ran++;
        }
        plan.queue.clear();
        totalDrained += ran;
        totalDropped += dropped;
        if (plan.deferredTicks > 0 || plan.flushedTicks > 0) {
            plan.token = nextToken++;
            Result result = invalid ? Result.INVALIDATED : Result.DEFERRED;
            record(new Receipt(plan.token, plan.worldId, plan.tickIndex, plan.worldEpoch,
                plan.deferredTicks, plan.flushedTicks, ran, dropped, result));
        }
        return ran;
    }

    /** Replays a plan whose safe point was missed, explicitly and in order, instead of clearing it. */
    private static void replayLate(Plan plan, Replay replay) {
        int ran = 0;
        while (!plan.queue.isEmpty()) {
            replay(plan.queue.removeFirst(), replay);
            ran++;
        }
        totalDrained += ran;
        plan.token = nextToken++;
        record(new Receipt(plan.token, plan.worldId, plan.tickIndex, plan.worldEpoch,
            plan.deferredTicks, plan.flushedTicks, ran, 0, Result.DEFERRED));
    }

    public static synchronized long deferredCount() {
        return totalDeferred;
    }

    public static synchronized long flushedCount() {
        return totalFlushed;
    }

    public static synchronized long drainedCount() {
        return totalDrained;
    }

    public static synchronized long droppedCount() {
        return totalDropped;
    }

    public static synchronized List<Receipt> receipts() {
        return List.copyOf(RECEIPTS);
    }

    public static synchronized void reset() {
        OPEN.clear();
        RECEIPTS.clear();
        nextToken = 1L;
        totalDeferred = 0L;
        totalFlushed = 0L;
        totalDrained = 0L;
        totalDropped = 0L;
        REPLAY_DEPTH.remove();
    }

    private static void flush(Plan plan, Replay replay) {
        int flushed = 0;
        while (!plan.queue.isEmpty()) {
            replay(plan.queue.removeFirst(), replay);
            flushed++;
        }
        plan.flushedTicks += flushed;
        totalFlushed += flushed;
    }

    private static void replay(Deferred record, Replay replay) {
        int depth = REPLAY_DEPTH.get();
        REPLAY_DEPTH.set(depth + 1);
        try {
            replay.update(record.level(), record.pos(), record.block(), record.facing());
        } finally {
            REPLAY_DEPTH.set(depth);
        }
    }

    private static void retire(Plan plan) {
        int dropped = plan.queue.size();
        plan.queue.clear();
        totalDropped += dropped;
        plan.token = nextToken++;
        record(new Receipt(plan.token, plan.worldId, plan.tickIndex, plan.worldEpoch,
            plan.deferredTicks, plan.flushedTicks, 0, dropped, Result.INVALIDATED));
    }

    private static void record(Receipt receipt) {
        RECEIPTS.addLast(receipt);
        while (RECEIPTS.size() > RECEIPT_LIMIT) {
            RECEIPTS.removeFirst();
        }
    }
}
