/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The version of one write right domain, keyed the same way the write right itself is keyed: the
 * value a write has to name before it is judged, and the value the planning period grants when it
 * declares a job over that domain. Every value comes from one monotone counter, so a retired value is
 * never handed out again and a write frozen against an old generation cannot match the slot that
 * replaced it. */
public final class WriteVersionSlots {

    /** What a write carries while its domain holds no slot. It is a declared absence and not a pass:
     * the decision point refuses an attempt that carries no version. */
    public static final long NOT_CARRIED = 0L;

    /** The instance a guard asks while no slot source is wired into it. */
    public static final WriteVersionSlots NOTHING = new WriteVersionSlots();

    private final Map<OwnershipDomain, Long> slots = new ConcurrentHashMap<>();
    private final GrantedWriteGenerations planGrants = new GrantedWriteGenerations();
    private final Set<String> worlds = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextVersion = new AtomicLong(1L);
    private final AtomicLong granted = new AtomicLong();
    private final AtomicLong advanced = new AtomicLong();
    private final AtomicLong worldDrops = new AtomicLong();
    private final AtomicLong reclaimed = new AtomicLong();
    private final AtomicLong carriedLookups = new AtomicLong();
    private final AtomicLong missingLookups = new AtomicLong();
    private volatile boolean enabled;

    /** Decides whether slots are handed out at all. Off, the planning period grants nothing, no
     * lookup is counted and every write carries {@link #NOT_CARRIED}. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The version a write of one domain has to name. */
    public long carried(String worldId, WriteLevel level, String domainId) {
        if (!enabled || worldId == null || domainId == null) {
            return NOT_CARRIED;
        }
        Long version = slots.get(new OwnershipDomain(worldId, level, domainId));
        if (version == null) {
            missingLookups.incrementAndGet();
            return NOT_CARRIED;
        }
        carriedLookups.incrementAndGet();
        return version;
    }

    /** The version a domain's slot carries right now, without counting a lookup: a review of the
     * right a plan handed out asks which value that right names, and asking must not move the reading
     * it is there to explain. A missing slot is the declared absence. */
    public long peek(String worldId, WriteLevel level, String domainId) {
        if (worldId == null || domainId == null) {
            return NOT_CARRIED;
        }
        Long version = slots.get(new OwnershipDomain(worldId, level, domainId));
        return version == null ? NOT_CARRIED : version;
    }

    /** Grants the slot of one declared write domain. The first grant mints a value, a later one
     * keeps the value already there, because only an invalidation retires a version. */
    public long grant(String worldId, WriteLevel level, String domainId) {
        if (!enabled || worldId == null || domainId == null) {
            return NOT_CARRIED;
        }
        OwnershipDomain domain = new OwnershipDomain(worldId, level, domainId);
        Long existing = slots.get(domain);
        if (existing != null) {
            return existing;
        }
        long minted = nextVersion.getAndIncrement();
        Long installed = slots.putIfAbsent(domain, minted);
        if (installed != null) {
            return installed;
        }
        granted.incrementAndGet();
        return minted;
    }

    /** Publishes the generations one plan's own grants returned, in one move. */
    public void noteGranted(long planSequence, Map<OwnershipDomain, Long> grantedByPlan) {
        planGrants.note(planSequence, grantedByPlan);
    }

    /** What one plan's own grant returned for one domain, or null when it granted nothing here. */
    public Long grantedFor(long planSequence, String worldId, WriteLevel level, String domainId) {
        return planGrants.grantedFor(planSequence, worldId, level, domainId);
    }

    public long grantedPlan() {
        return planGrants.planSequence();
    }

    /** Retires the version one slot carries and hands out a fresh one in its place, so a write that
     * was frozen against the old value is left behind. */
    public long advance(String worldId, WriteLevel level, String domainId) {
        if (!enabled || worldId == null || domainId == null) {
            return NOT_CARRIED;
        }
        OwnershipDomain domain = new OwnershipDomain(worldId, level, domainId);
        if (!slots.containsKey(domain)) {
            return NOT_CARRIED;
        }
        long minted = nextVersion.getAndIncrement();
        slots.put(domain, minted);
        advanced.incrementAndGet();
        return minted;
    }

    /** Observes the worlds the platform reports live. A world that left the set has every slot of it
     * retired before the slot is reclaimed, so a world that appears again is a new generation and
     * nothing of the old one can match it. */
    public int noteWorlds(List<String> worldIds) {
        if (!enabled || worldIds == null) {
            return 0;
        }
        Set<String> live = new HashSet<>(worldIds);
        live.remove(null);
        worlds.addAll(live);
        List<String> dropped = new ArrayList<>();
        for (String world : worlds) {
            if (!live.contains(world)) {
                dropped.add(world);
            }
        }
        int count = 0;
        for (String world : dropped) {
            worlds.remove(world);
            count += retireWorld(world);
            worldDrops.incrementAndGet();
        }
        return count;
    }

    /** Drops every slot. The version counter keeps rising, so no value from before the reset comes
     * back. */
    public void reset() {
        reclaimed.addAndGet(slots.size());
        slots.clear();
        worlds.clear();
        planGrants.reset();
    }

    public int activeSlots() {
        return slots.size();
    }

    public long grantedCount() {
        return granted.get();
    }

    public long advancedCount() {
        return advanced.get();
    }

    public long worldDropCount() {
        return worldDrops.get();
    }

    public long reclaimedCount() {
        return reclaimed.get();
    }

    public long carriedCount() {
        return carriedLookups.get();
    }

    public long notCarriedCount() {
        return missingLookups.get();
    }

    private int retireWorld(String worldId) {
        List<OwnershipDomain> owned = new ArrayList<>();
        for (OwnershipDomain domain : slots.keySet()) {
            if (domain.worldId().equals(worldId)) {
                owned.add(domain);
            }
        }
        for (OwnershipDomain domain : owned) {
            if (slots.remove(domain) != null) {
                advanced.incrementAndGet();
                reclaimed.incrementAndGet();
            }
        }
        return owned.size();
    }
}
