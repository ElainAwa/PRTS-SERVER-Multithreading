/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The bounded DEVICE execution boundary of one level tick: the planned wall budget of the DEVICE
 * class decides how many block-entity ticks run inside the level's own block-entity phase and the
 * rest wait for that same tick's safe point, the RETURN of the very {@code tickBlockEntities} call
 * (the module-level drain is only the fallback). A deferred tick is not skipped, not run in parallel
 * and not rewritten: it is called exactly once, unchanged, on the same server thread inside the same
 * tick; only the point inside the tick moves. The plan is keyed by the level instance and carries
 * the world id, the kernel tick, the world lifecycle epoch and the budget it was admitted under; the
 * queue and the receipts are bounded, a repeated begin/drain of one plan is a no-op, and a world
 * whose lifecycle epoch moved drops its deferred ticks without reporting an action.
 *
 * <p>The state is owned by the server thread; every entry point is synchronized so a readout taken
 * from another thread cannot see a half-updated plan.
 */
public final class DeviceTickDeferral {

    /** Most deferred ticks one world may carry into its safe point; above it the tick runs in place. */
    public static final int DEFERRED_LIMIT = 64;

    /** Most receipts kept; the older ones fall out so the evidence stays bounded. */
    public static final int RECEIPT_LIMIT = 64;

    /** How one deferred batch ended at the safe point. */
    public enum Result {
        DEFERRED,
        DEFERRED_PARTIAL,
        INVALIDATED
    }

    /** One over-budget plan and what happened to the ticks it deferred. */
    public record Receipt(long token, String worldId, long tickIndex, long worldEpoch,
                          long budgetNanos, long usedNanos, int inlineTicks, int deferredTicks,
                          int forcedTicks, int drainedTicks, int droppedTicks, Result result) {
    }

    private static final class Plan {

        private final String worldId;
        private final long tickIndex;
        private final long worldEpoch;
        private final long budgetNanos;
        private long usedNanos;
        private boolean deferring;
        private boolean closed;
        private int inlineTicks;
        private int forcedTicks;
        private int deferredTicks;
        private long token;
        private final ArrayDeque<TickingBlockEntity> deferred = new ArrayDeque<>();

        private Plan(String worldId, long tickIndex, long worldEpoch, long budgetNanos) {
            this.worldId = worldId;
            this.tickIndex = tickIndex;
            this.worldEpoch = worldEpoch;
            this.budgetNanos = budgetNanos;
        }
    }

    private static final Map<Object, Plan> OPEN = new LinkedHashMap<>();
    private static final Map<Object, Plan> WAITING = new LinkedHashMap<>();
    private static final ArrayDeque<Receipt> RECEIPTS = new ArrayDeque<>();
    private static long nextToken = 1L;
    private static long overPlans;
    private static long forcedTicks;
    private static long droppedTicks;

    private DeviceTickDeferral() {
    }

    /** Opens the plan of one level tick; a repeated call for the same identity keeps the plan. */
    public static synchronized boolean begin(Object scope, String worldId, long tickIndex,
                                             long worldEpoch, long budgetNanos, boolean enabled) {
        if (scope == null || worldId == null || worldId.isEmpty()) {
            return false;
        }
        if (!enabled || budgetNanos <= 0L) {
            OPEN.remove(scope);
            return false;
        }
        Plan current = OPEN.get(scope);
        if (current != null && !current.closed && current.tickIndex == tickIndex
            && current.worldEpoch == worldEpoch) {
            return true;
        }
        OPEN.remove(scope);
        OPEN.put(scope, new Plan(worldId, tickIndex, worldEpoch, budgetNanos));
        return true;
    }

    /** True while a plan of this scope is open; the tick path reads it before it measures. */
    public static synchronized boolean active(Object scope) {
        Plan plan = scope == null ? null : OPEN.get(scope);
        return plan != null && !plan.closed;
    }

    /** True when this tick waits for the safe point instead of running in its phase. */
    public static synchronized boolean defer(Object scope, TickingBlockEntity ticker) {
        if (OPEN.isEmpty()) {
            return false;
        }
        Plan plan = scope == null ? null : OPEN.get(scope);
        if (plan == null || plan.closed || ticker == null) {
            return false;
        }
        if (!plan.deferring && plan.usedNanos < plan.budgetNanos) {
            return false;
        }
        plan.deferring = true;
        if (plan.deferred.size() >= DEFERRED_LIMIT) {
            plan.forcedTicks++;
            forcedTicks++;
            return false;
        }
        plan.deferred.addLast(ticker);
        return true;
    }

    /** Adds the wall time of one tick that ran inside the phase. */
    public static synchronized void noteInline(Object scope, long wallNanos) {
        Plan plan = scope == null ? null : OPEN.get(scope);
        if (plan == null || plan.closed) {
            return;
        }
        plan.usedNanos += Math.max(0L, wallNanos);
        plan.inlineTicks++;
    }

    /** Closes the plan of this tick; only an over-budget plan waits for the safe point. */
    public static synchronized void end(Object scope, long tickIndex, long worldEpoch) {
        Plan plan = scope == null ? null : OPEN.get(scope);
        if (plan == null || plan.closed || plan.tickIndex != tickIndex
            || plan.worldEpoch != worldEpoch) {
            return;
        }
        plan.closed = true;
        OPEN.remove(scope);
        if (plan.deferred.isEmpty()) {
            return;
        }
        plan.token = nextToken++;
        plan.deferredTicks = plan.deferred.size();
        overPlans++;
        Plan previous = WAITING.put(scope, plan);
        if (previous != null) {
            // A safe point was missed: the older deferred work runs first, in its own order.
            while (!previous.deferred.isEmpty()) {
                plan.deferred.addFirst(previous.deferred.removeLast());
            }
            plan.inlineTicks += previous.inlineTicks;
            plan.forcedTicks += previous.forcedTicks;
            plan.usedNanos += previous.usedNanos;
            plan.deferredTicks += previous.deferredTicks;
        }
    }

    /** True while one scope still waits for its own tick's safe point. */
    public static synchronized boolean hasWaiting(Object scope) {
        return scope != null && WAITING.containsKey(scope);
    }

    /** Runs the waiting plan of one scope at its own tick's RETURN; a second call finds nothing. */
    public static synchronized int drainScope(Object scope, ToLongFunction<String> worldEpoch) {
        Plan plan = scope == null ? null : WAITING.remove(scope);
        return plan == null ? 0 : drainPlan(plan, worldEpoch);
    }

    /** Drops every plan of one world at its unload; a deferred tick of an unloaded level is not
     * run and is booked as dropped, so a later safe point cannot execute it on a dead level. */
    public static synchronized int forgetWorld(String worldId) {
        int dropped = 0;
        if (worldId == null) {
            return 0;
        }
        Iterator<Map.Entry<Object, Plan>> open = OPEN.entrySet().iterator();
        while (open.hasNext()) {
            Plan plan = open.next().getValue();
            if (worldId.equals(plan.worldId)) {
                dropped += plan.deferred.size();
                open.remove();
            }
        }
        Iterator<Map.Entry<Object, Plan>> waiting = WAITING.entrySet().iterator();
        while (waiting.hasNext()) {
            Plan plan = waiting.next().getValue();
            if (worldId.equals(plan.worldId)) {
                dropped += plan.deferred.size();
                waiting.remove();
            }
        }
        droppedTicks += dropped;
        return dropped;
    }

    /** Fallback safe point: runs every waiting plan a level RETURN did not drain. */
    public static synchronized int drain(ToLongFunction<String> worldEpoch) {
        int drained = 0;
        for (Object scope : new ArrayList<>(WAITING.keySet())) {
            Plan plan = WAITING.remove(scope);
            if (plan != null) {
                drained += drainPlan(plan, worldEpoch);
            }
        }
        return drained;
    }

    private static int drainPlan(Plan plan, ToLongFunction<String> worldEpoch) {
        boolean invalid = worldEpoch == null
            || worldEpoch.applyAsLong(plan.worldId) != plan.worldEpoch;
        int ran = 0;
        int dropped = 0;
        for (TickingBlockEntity ticker : plan.deferred) {
            if (invalid || ticker == null || ticker.isRemoved()) {
                dropped++;
                continue;
            }
            ticker.tick();
            ran++;
        }
        droppedTicks += dropped;
        Result result = invalid || ran == 0 ? Result.INVALIDATED
            : dropped > 0 ? Result.DEFERRED_PARTIAL : Result.DEFERRED;
        RECEIPTS.addLast(new Receipt(plan.token, plan.worldId, plan.tickIndex,
            plan.worldEpoch, plan.budgetNanos, plan.usedNanos, plan.inlineTicks,
            plan.deferredTicks, plan.forcedTicks, ran, dropped, result));
        while (RECEIPTS.size() > RECEIPT_LIMIT) {
            RECEIPTS.removeFirst();
        }
        return ran;
    }

    public static synchronized long overCount() {
        return overPlans;
    }

    public static synchronized long forcedCount() {
        return forcedTicks;
    }

    public static synchronized long droppedCount() {
        return droppedTicks;
    }

    public static synchronized List<Receipt> receipts() {
        return List.copyOf(RECEIPTS);
    }

    public static synchronized void reset() {
        OPEN.clear();
        WAITING.clear();
        RECEIPTS.clear();
        overPlans = 0L;
        forcedTicks = 0L;
        droppedTicks = 0L;
        nextToken = 1L;
    }
}
