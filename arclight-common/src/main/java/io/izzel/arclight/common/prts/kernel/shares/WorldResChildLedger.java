/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.LinkedHashMap;
import java.util.Map;

/** The first-slice settlement ledger: one parent world-resource row per world, tick and epoch is
 * accepted once, so a repeated settle cannot count the parent twice. An epoch change opens a fresh
 * allowance generation and drops the old settlements; the ledger is internal and is not wired to any
 * production path or readout key. The metering action stays NOT_WIRED. */
public final class WorldResChildLedger {

    private final Map<Key, WorldResChildBudget> settlements = new LinkedHashMap<>();
    private long epoch;
    private double countedParentMs;

    /** Opens the ledger in one epoch. */
    public WorldResChildLedger(long epoch) {
        this.epoch = epoch;
    }

    public synchronized long epoch() {
        return epoch;
    }

    /** The parent amounts the ledger counted, once per accepted world/tick/epoch. */
    public synchronized double countedParentMs() {
        return countedParentMs;
    }

    public synchronized int settlementCount() {
        return settlements.size();
    }

    /** Settles one world's child budget. A second settle of the same world, tick and epoch answers
     * the first settlement as a duplicate and counts the parent again for nothing. A newer epoch
     * resets the ledger before the settlement, so an old allowance cannot be reused. */
    public synchronized Settlement settle(ShareTable table, String worldId, long epoch,
                                          WorldResChildBudget.ChildDemand demand, double otherMs,
                                          double marginMs) {
        if (epoch != this.epoch) {
            this.epoch = epoch;
            settlements.clear();
            countedParentMs = 0.0;
        }
        Key key = new Key(worldId, table == null ? -1L : table.tickIndex(), epoch);
        WorldResChildBudget existing = settlements.get(key);
        if (existing != null) {
            return new Settlement(existing, false, true);
        }
        WorldResChildBudget budget = WorldResChildBudget.settle(table, worldId, epoch, demand,
            otherMs, marginMs);
        settlements.put(key, budget);
        if (budget.status() != WorldResChildBudget.Status.NO_PARENT
            && budget.status() != WorldResChildBudget.Status.INVALID_INPUT) {
            countedParentMs += budget.parentShareMs();
        }
        return new Settlement(budget, true, false);
    }

    /** One answer of the ledger: the immutable budget, whether this call counted the parent, and
     * whether it was the duplicate of an accepted settlement. */
    public record Settlement(WorldResChildBudget budget, boolean counted, boolean duplicate) {
    }

    private record Key(String worldId, long tickIndex, long epoch) {
    }
}
