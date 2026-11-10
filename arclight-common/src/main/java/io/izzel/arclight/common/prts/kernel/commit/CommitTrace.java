/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded trace of committed seats: three capacities (plans, seats, worlds), a monotone sequence
 * and a self-check that reports whether the retained world set is within its bound. */
public final class CommitTrace {

    private final int planCapacity;
    private final int seatCapacity;
    private final int worldCapacity;
    private final Set<Long> plans = new LinkedHashSet<>();
    private final Set<Long> seats = new LinkedHashSet<>();
    private final Set<String> worlds = new LinkedHashSet<>();
    private long recorded;
    private long dropped;

    public CommitTrace(int planCapacity, int seatCapacity, int worldCapacity) {
        this.planCapacity = Math.max(1, planCapacity);
        this.seatCapacity = Math.max(1, seatCapacity);
        this.worldCapacity = Math.max(1, worldCapacity);
    }

    /** Records one seat; excess plan/seat/world entries are dropped and counted. */
    public synchronized boolean record(long planSequence, int position, String worldId) {
        boolean room = plans.size() < planCapacity || plans.contains(planSequence);
        room &= seats.size() < seatCapacity || seats.contains(position);
        room &= worlds.size() < worldCapacity || (worldId != null && worlds.contains(worldId));
        if (!room) {
            dropped++;
            return false;
        }
        plans.add(planSequence);
        seats.add((long) position);
        if (worldId != null) worlds.add(worldId);
        recorded++;
        return true;
    }

    /** True when all three retained sets stay inside their capacities. */
    public synchronized boolean selfCheck() {
        return plans.size() <= planCapacity && seats.size() <= seatCapacity
            && worlds.size() <= worldCapacity;
    }

    /** Number of seats accepted into the trace. */
    public synchronized long recorded() { return recorded; }
    /** Number of seats refused after a capacity was exceeded. */
    public synchronized long dropped() { return dropped; }
    public synchronized Set<String> worlds() { return Set.copyOf(worlds); }
    public synchronized int planCapacity() { return planCapacity; }
    public synchronized int seatCapacity() { return seatCapacity; }
    public synchronized int worldCapacity() { return worldCapacity; }
}
