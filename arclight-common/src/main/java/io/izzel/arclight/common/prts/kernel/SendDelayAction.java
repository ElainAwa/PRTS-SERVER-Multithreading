/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** The bounded send-delay decision. Under a fresh B01-A parent-over settlement it asks the existing
 * PlayerChunkSender to defer one send tick; the host's own pending set remains the only chunk
 * queue, so nothing is dropped or duplicated. The delay is capped by ticks, worlds and the host
 * pending size and fails open on any unreadable or stale input. */
public final class SendDelayAction {

    /** The maximum number of worlds whose delay state is retained. */
    public static final int MAX_WORLDS = 64;
    /** The maximum host pending count at which a send can be deferred. */
    public static final int MAX_PENDING = 4096;
    /** The maximum age of a settlement considered current, in ticks. */
    public static final long MAX_BUDGET_AGE_TICKS = 2L;
    /** The maximum number of consecutive deferred ticks for one world and epoch. */
    public static final long MAX_DELAY_TICKS = 2L;

    private static final Object LOCK = new Object();
    private static final Map<String, DelayState> STATES = new HashMap<>();
    private static final Map<String, DelayedCountState> COUNTS =
        new LinkedHashMap<>(16, 0.75f, true);
    private static volatile boolean installed;
    private static LongSupplier tickSource = () -> 0L;
    private static ToLongFunction<String> epochSource = world -> WorldEpochs.UNTRACKED;
    private static Supplier<List<WorldResChildBudget>> settlementSource = List::of;

    private SendDelayAction() {
    }

    /** Installs or removes the action. It follows the existing kernel and child-meter switch; a
     * disable drops all retained states and no future send is deferred. */
    public static void install(boolean enabled, LongSupplier ticks,
                               ToLongFunction<String> epochs,
                               Supplier<List<WorldResChildBudget>> settlements) {
        synchronized (LOCK) {
            installed = enabled;
            tickSource = ticks == null ? () -> 0L : ticks;
            epochSource = epochs == null ? world -> WorldEpochs.UNTRACKED : epochs;
            settlementSource = settlements == null ? List::of : settlements;
            if (!enabled) {
                STATES.clear();
                COUNTS.clear();
            }
        }
    }

    /** Answers whether the host should skip its next chunk-send pass for this world. The decision
     * never stores a chunk; the host pending set is the only queue and an unreachable input fails
     * open so the host sends normally. */
    public static boolean shouldDefer(String worldId, int pendingChunks) {
        if (!installed || worldId == null) {
            return false;
        }
        try {
            synchronized (LOCK) {
                long now = tickSource.getAsLong();
                if (now < 0L) {
                    STATES.remove(worldId);
                    return false;
                }
                long epoch = epochSource.applyAsLong(worldId);
                if (epoch <= WorldEpochs.UNTRACKED) {
                    STATES.remove(worldId);
                    return false;
                }
                if (pendingChunks <= 0 || pendingChunks > MAX_PENDING) {
                    STATES.remove(worldId);
                    return false;
                }
                WorldResChildBudget budget = latestBudgetLocked(worldId, epoch, now);
                if (budget == null || budget.status() != WorldResChildBudget.Status.PARENT_OVER) {
                    STATES.remove(worldId);
                    return false;
                }
                DelayState state = STATES.get(worldId);
                if (state == null || state.epoch != epoch) {
                    if (state != null) {
                        STATES.remove(worldId);
                    }
                    if (STATES.size() >= MAX_WORLDS) {
                        return false;
                    }
                    state = new DelayState(epoch, now);
                    STATES.put(worldId, state);
                }
                if (now - state.firstTick > MAX_DELAY_TICKS) {
                    STATES.remove(worldId);
                    return false;
                }
                state.lastTick = now;
                countDelayedLocked(worldId, epoch);
                return true;
            }
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Drops one world's retained delay state, as unload does. */
    public static void forgetWorld(String worldId) {
        if (worldId == null) {
            return;
        }
        synchronized (LOCK) {
            STATES.remove(worldId);
            COUNTS.remove(worldId);
        }
    }

    /** Drops every retained delay state and delayed-pass count but keeps the install state. */
    public static void reset() {
        synchronized (LOCK) {
            STATES.clear();
            COUNTS.clear();
        }
    }

    /** True while the action is installed; the readout emits delay.send_count only in this state. */
    public static boolean installed() {
        return installed;
    }

    /** The real delayed-send pass count of the current live generations: one per cancelled
     * PlayerChunkSender.sendNextChunks call, not per pending chunk. A world whose epoch changed or
     * was unloaded is removed; an off action answers zero and the readout omits the key. */
    public static long delayedPasses() {
        synchronized (LOCK) {
            if (!installed) {
                return 0L;
            }
            long total = 0L;
            Iterator<Map.Entry<String, DelayedCountState>> iterator = COUNTS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, DelayedCountState> entry = iterator.next();
                long epoch;
                try {
                    epoch = epochSource.applyAsLong(entry.getKey());
                } catch (Throwable unreadable) {
                    iterator.remove();
                    continue;
                }
                if (epoch <= WorldEpochs.UNTRACKED || epoch != entry.getValue().epoch) {
                    iterator.remove();
                    continue;
                }
                total = total > Long.MAX_VALUE - entry.getValue().count
                    ? Long.MAX_VALUE : total + entry.getValue().count;
            }
            return total;
        }
    }

    private static void countDelayedLocked(String worldId, long epoch) {
        DelayedCountState count = COUNTS.get(worldId);
        if (count == null || count.epoch != epoch) {
            COUNTS.remove(worldId);
            if (COUNTS.size() >= MAX_WORLDS) {
                Iterator<String> oldest = COUNTS.keySet().iterator();
                if (oldest.hasNext()) {
                    COUNTS.remove(oldest.next());
                }
            }
            count = new DelayedCountState(epoch);
            COUNTS.put(worldId, count);
        }
        if (count.count < Long.MAX_VALUE) {
            count.count++;
        }
    }

    private static WorldResChildBudget latestBudgetLocked(String worldId, long epoch, long now) {
        List<WorldResChildBudget> settlements = settlementSource.get();
        if (settlements == null || settlements.isEmpty()) {
            return null;
        }
        WorldResChildBudget latest = null;
        for (WorldResChildBudget budget : new ArrayList<>(settlements)) {
            if (budget == null || !worldId.equals(budget.worldId()) || budget.epoch() != epoch) {
                continue;
            }
            long tick = budget.tickIndex();
            if (tick < 0L || tick > now || now - tick > MAX_BUDGET_AGE_TICKS) {
                continue;
            }
            if (latest == null || tick >= latest.tickIndex()) {
                latest = budget;
            }
        }
        return latest;
    }

    private static final class DelayedCountState {

        private final long epoch;
        private long count;

        private DelayedCountState(long epoch) {
            this.epoch = epoch;
        }
    }

    private static final class DelayState {

        private final long epoch;
        private final long firstTick;
        private long lastTick;

        private DelayState(long epoch, long firstTick) {
            this.epoch = epoch;
            this.firstTick = firstTick;
            this.lastTick = firstTick;
        }
    }
}
