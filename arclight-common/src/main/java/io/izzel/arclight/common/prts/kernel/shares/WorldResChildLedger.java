/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The internal settlement ledger: one parent world-resource row per world, tick and epoch is
 * accepted once. Each world keeps its own epoch and last tick, so an old report of one world cannot
 * reject a newer report of another. Settlements are bounded by count and by tick age, the worlds
 * they belong to are bounded too, and an evicted or stale settlement is never counted again. Unload
 * and reset remove a world's settlements; the parent amount is counted once per retained
 * world/tick/epoch. The ledger is internal: no readout key names it. */
public final class WorldResChildLedger {

    /** How many settlements are kept at once; the oldest tick is evicted past this bound. */
    public static final int MAX_SETTLEMENTS = 256;

    /** How many ticks a settlement may stay in the ledger after its tick; older ticks are evicted
     * when a newer tick arrives. */
    public static final long RETENTION_TICKS = 128L;

    /** How many worlds are tracked at once; the least recently used world is retired past this
     * bound. A retired world is still refused its old epoch for one bounded tombstone window. */
    public static final int MAX_WORLDS = 64;

    private static final long NONE = Long.MIN_VALUE;

    private static final class WorldState {

        private long epoch = NONE;
        private long lastTick = NONE;
        private final Map<Long, WorldResChildBudget> byTick = new LinkedHashMap<>();
        private double countedParentMs;
    }

    private record Tombstone(long epoch, long lastTick) {
    }

    private final Map<String, WorldState> worlds = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Tombstone> retired = new LinkedHashMap<>();
    private final long openingEpoch;
    private double countedParentMs;
    private long lastEpoch;
    private int staleRejections;
    private int evictions;
    private int retiredWorlds;

    /** Opens the ledger at an epoch: it is the value {@link #epoch()} answers before any settlement
     * and the value a reset returns to. The per-world positions of the first reports are theirs. */
    public WorldResChildLedger(long epoch) {
        this.openingEpoch = epoch;
        this.lastEpoch = epoch;
    }

    /** The newest epoch the ledger accepted; per-world epochs are read with {@link #epochOf}. */
    public synchronized long epoch() {
        return lastEpoch;
    }

    /** One world's accepted epoch, or {@link Long#MIN_VALUE} while the world is unknown. */
    public synchronized long epochOf(String worldId) {
        WorldState state = worldId == null ? null : worlds.get(worldId);
        return state == null ? NONE : state.epoch;
    }

    /** The parent amounts retained and counted, once per retained world/tick/epoch. */
    public synchronized double countedParentMs() {
        return countedParentMs;
    }

    public synchronized int settlementCount() {
        int total = 0;
        for (WorldState state : worlds.values()) {
            total += state.byTick.size();
        }
        return total;
    }

    /** Reports rejected because their epoch or tick was older than the world's accepted position. */
    public synchronized int staleRejections() {
        return staleRejections;
    }

    /** Settlements dropped by the count bound, the tick retention bound or the world bound. */
    public synchronized int evictions() {
        return evictions;
    }

    /** Drops every settlement of one world, as an unload does. The world's accepted position is
     * kept as one bounded tombstone, so a late report of the old epoch or tick is stale and is not
     * counted again. */
    public synchronized void forgetWorld(String worldId) {
        WorldState state = worldId == null ? null : worlds.remove(worldId);
        if (state == null) {
            return;
        }
        drop(state, false);
        remember(worldId, state.epoch, state.lastTick);
    }

    /** Drops every settlement, every tombstone and every per-world epoch. */
    public synchronized void reset() {
        worlds.clear();
        retired.clear();
        countedParentMs = 0.0;
        staleRejections = 0;
        evictions = 0;
        retiredWorlds = 0;
        lastEpoch = openingEpoch;
    }

    /** Settles one world's child budget. A stale epoch, a stale tick or a duplicate world/tick/epoch
     * answers without counting the parent; a newer epoch opens that world's fresh allowance and a
     * newer tick evicts the settlements older than the retention window. */
    public synchronized Settlement settle(ShareTable table, String worldId, long epoch,
                                          WorldResChildBudget.ChildDemand demand, double otherMs,
                                          double marginMs) {
        if (worldId == null || worldId.isBlank()) {
            return new Settlement(null, false, false, true);
        }
        WorldState state = worlds.get(worldId);
        if (state == null) {
            state = open(worldId, epoch);
            if (state == null) {
                staleRejections++;
                return new Settlement(null, false, false, true);
            }
        }
        if (epoch < state.epoch) {
            staleRejections++;
            return new Settlement(null, false, false, true);
        }
        if (epoch > state.epoch) {
            for (WorldResChildBudget budget : state.byTick.values()) {
                subtract(state, budget);
            }
            state.byTick.clear();
            state.epoch = epoch;
            state.lastTick = NONE;
        }
        long tick = table == null ? -1L : table.tickIndex();
        if (tick < state.lastTick) {
            staleRejections++;
            return new Settlement(null, false, false, true);
        }
        WorldResChildBudget existing = state.byTick.get(tick);
        if (existing != null) {
            return new Settlement(existing, false, true, false);
        }
        if (tick == state.lastTick) {
            // the one settlement this world had at this tick was evicted or retired; a report that
            // repeats it must not count the parent a second time
            staleRejections++;
            return new Settlement(null, false, false, true);
        }
        evictBefore(state, tick);
        while (settlementCount() >= MAX_SETTLEMENTS && evictOldest()) {
            // a full ledger makes room before the next settlement is stored
        }
        WorldResChildBudget budget = WorldResChildBudget.settle(table, worldId, epoch, demand,
            otherMs, marginMs);
        state.byTick.put(tick, budget);
        state.lastTick = tick;
        lastEpoch = Math.max(lastEpoch, epoch);
        if (countable(budget)) {
            state.countedParentMs += budget.parentShareMs();
            countedParentMs += budget.parentShareMs();
        }
        return new Settlement(budget, true, false, false);
    }

    /** Opens the state of a world the ledger does not track yet; a report older than the world's
     * tombstone answers null as stale. */
    private WorldState open(String worldId, long epoch) {
        Tombstone tomb = retired.remove(worldId);
        WorldState state = new WorldState();
        if (tomb != null) {
            if (epoch < tomb.epoch()) {
                return null;
            }
            state.epoch = tomb.epoch();
            state.lastTick = tomb.lastTick();
        }
        if (state.epoch == NONE) {
            // The first report of a new world defines its position: epochs are per world and a
            // report of another world cannot make this one stale.
            state.epoch = epoch;
        }
        worlds.put(worldId, state);
        trimWorlds();
        return state;
    }

    private void trimWorlds() {
        while (worlds.size() > MAX_WORLDS) {
            Map.Entry<String, WorldState> oldest = worlds.entrySet().iterator().next();
            worlds.remove(oldest.getKey());
            drop(oldest.getValue(), true);
            remember(oldest.getKey(), oldest.getValue().epoch, oldest.getValue().lastTick);
            retiredWorlds++;
        }
    }

    private void drop(WorldState state, boolean countedAsEviction) {
        for (WorldResChildBudget budget : state.byTick.values()) {
            subtract(state, budget);
            if (countedAsEviction) {
                evictions++;
            }
        }
        state.byTick.clear();
    }

    private void remember(String worldId, long epoch, long lastTick) {
        if (epoch == NONE) {
            return;
        }
        retired.remove(worldId);
        retired.put(worldId, new Tombstone(epoch, lastTick));
        while (retired.size() > MAX_WORLDS) {
            Map.Entry<String, Tombstone> oldest = retired.entrySet().iterator().next();
            retired.remove(oldest.getKey());
        }
    }

    private void evictBefore(WorldState state, long tick) {
        long cutoff = tick - RETENTION_TICKS;
        var iterator = state.byTick.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, WorldResChildBudget> entry = iterator.next();
            if (entry.getKey() < cutoff) {
                subtract(state, entry.getValue());
                iterator.remove();
                evictions++;
            }
        }
    }

    private boolean evictOldest() {
        String oldestWorld = null;
        long oldestTick = Long.MAX_VALUE;
        for (Map.Entry<String, WorldState> entry : worlds.entrySet()) {
            for (Long tick : entry.getValue().byTick.keySet()) {
                if (tick < oldestTick) {
                    oldestTick = tick;
                    oldestWorld = entry.getKey();
                }
            }
        }
        if (oldestWorld == null) {
            return false;
        }
        WorldState state = worlds.get(oldestWorld);
        if (state == null) {
            return false;
        }
        WorldResChildBudget removed = state.byTick.remove(oldestTick);
        if (removed == null) {
            return false;
        }
        subtract(state, removed);
        evictions++;
        return true;
    }

    private static boolean countable(WorldResChildBudget budget) {
        return budget.status() != WorldResChildBudget.Status.NO_PARENT
            && budget.status() != WorldResChildBudget.Status.INVALID_INPUT;
    }

    private void subtract(WorldState state, WorldResChildBudget budget) {
        if (countable(budget)) {
            state.countedParentMs -= budget.parentShareMs();
            countedParentMs -= budget.parentShareMs();
        }
    }

    /** The retained settlements, in world and tick order; internal, no readout key names them. */
    public synchronized List<WorldResChildBudget> settlements() {
        List<WorldResChildBudget> all = new ArrayList<>();
        for (WorldState state : worlds.values()) {
            all.addAll(state.byTick.values());
        }
        return all;
    }

    /** How many worlds the ledger tracks; package internal. */
    synchronized int trackedWorlds() {
        return worlds.size();
    }

    /** How many tombstones the ledger keeps; package internal. */
    synchronized int retiredWorlds() {
        return retired.size();
    }

    /** One answer of the ledger: the immutable budget (null for a stale report), whether this call
     * counted the parent, whether it was a duplicate, and whether it was stale by epoch or tick. */
    public record Settlement(WorldResChildBudget budget, boolean counted, boolean duplicate,
                             boolean stale) {
    }
}
