/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The resources one cross-entity interaction touches, as stable keys: the attacker and the victim
 * as entity rows, and the health, inventory, durability and point-of-interest record domains. The
 * keys are sorted into one deterministic order and admitted all or none: a partially owned set
 * takes nothing and the caller is told which kind of refusal it found.
 */
public final class InteractionResources {

    /** What one all-or-none admission did; only {@link #ADMITTED} may run the action. */
    public enum Verdict {
        ADMITTED,
        REFUSED_OWNERSHIP,
        REFUSED_VERSION
    }

    /** The two rows of one interaction, in the deterministic pair order. */
    public record PairKey(EntityRowIdentity attacker, EntityRowIdentity victim) {

        public PairKey {
            Objects.requireNonNull(attacker, "attacker");
            Objects.requireNonNull(victim, "victim");
        }

        /** One pair whose attacker and victim are put in the stable order, never by call order. */
        public static PairKey canonical(EntityRowIdentity first, EntityRowIdentity second) {
            return compareRows(first, second) <= 0 ? new PairKey(first, second)
                : new PairKey(second, first);
        }

        /** The pair rows in the same stable order used inside the resource keys. */
        public List<EntityRowIdentity> ordered() {
            return List.of(attacker, victim);
        }
    }

    /** The entity state domain of one row. */
    public static final String KIND_ENTITY_STATE = "entity-state";
    /** The health domain of one row. */
    public static final String KIND_HEALTH = "entity-health";
    /** The inventory domain of one row. */
    public static final String KIND_INVENTORY = "entity-inventory";
    /** The durability domain of one row. */
    public static final String KIND_DURABILITY = "entity-durability";
    /** The occupancy domain of one point-of-interest record. */
    public static final String KIND_POI_OCCUPANCY = "poi-occupancy";

    private InteractionResources() {
    }

    /** The entity state key of one row at its lifecycle generation. */
    public static OwnerRegistry.OwnershipDomain entityState(EntityRowIdentity identity,
                                                            long objectGeneration) {
        return domain(identity.worldId(), KIND_ENTITY_STATE + ":" + identity.uuidHigh() + ":"
            + identity.uuidLow() + ":" + objectGeneration);
    }

    /** The health key of one row. */
    public static OwnerRegistry.OwnershipDomain health(EntityRowIdentity identity) {
        return domain(identity.worldId(), KIND_HEALTH + ":" + identity.uuidHigh() + ":"
            + identity.uuidLow());
    }

    /** The inventory key of one row. */
    public static OwnerRegistry.OwnershipDomain inventory(EntityRowIdentity identity) {
        return domain(identity.worldId(), KIND_INVENTORY + ":" + identity.uuidHigh() + ":"
            + identity.uuidLow());
    }

    /** The durability key of one row. */
    public static OwnerRegistry.OwnershipDomain durability(EntityRowIdentity identity) {
        return domain(identity.worldId(), KIND_DURABILITY + ":" + identity.uuidHigh() + ":"
            + identity.uuidLow());
    }

    /** The occupancy key of one point-of-interest record. */
    public static OwnerRegistry.OwnershipDomain poiOccupancy(String worldId, String recordKey) {
        Objects.requireNonNull(recordKey, "recordKey");
        if (recordKey.isEmpty()) {
            throw new IllegalArgumentException("a point-of-interest record needs its key");
        }
        return domain(worldId, KIND_POI_OCCUPANCY + ":" + recordKey);
    }

    /** The keys of one attack pair in the stable order, plus the optional record occupancy key. */
    public static List<OwnerRegistry.OwnershipDomain> keys(PairKey pair, boolean health,
                                                           boolean inventory, boolean durability,
                                                           OwnerRegistry.OwnershipDomain occupancy) {
        List<OwnerRegistry.OwnershipDomain> keys = new ArrayList<>(9);
        for (EntityRowIdentity row : pair.ordered()) {
            keys.add(entityState(row, 0L));
            if (health) {
                keys.add(health(row));
            }
            if (inventory) {
                keys.add(inventory(row));
            }
            if (durability) {
                keys.add(durability(row));
            }
        }
        if (occupancy != null) {
            keys.add(occupancy);
        }
        keys.sort(DOMAIN_ORDER);
        return List.copyOf(keys);
    }

    /** The tokens one admission asks for; the expected version is the slot's carried version. */
    public static List<OwnerToken> tokens(List<OwnerRegistry.OwnershipDomain> keys,
                                          WriteVersionSlots versions, String holderSite,
                                          long epoch, long holdStartTick, long expireTick) {
        List<OwnerToken> tokens = new ArrayList<>(keys.size());
        for (OwnerRegistry.OwnershipDomain key : keys) {
            long version = versions == null ? WriteVersionSlots.NOT_CARRIED
                : versions.carried(key.worldId(), key.level(), key.domainId());
            tokens.add(new OwnerToken(key.worldId(), key.level(), key.domainId(), epoch, version,
                holdStartTick, expireTick, HolderKind.REGISTERED, holderSite));
        }
        return List.copyOf(tokens);
    }

    /**
     * Admits every key or nothing: the slots are read again before the action, a held or stale key
     * refuses without running the action, and an admitted call hands every fresh right back.
     */
    public static Verdict admitAll(OwnerRegistry owners, WriteVersionSlots versions,
                                   List<OwnerRegistry.OwnershipDomain> keys, String holderSite,
                                   Runnable action) {
        Objects.requireNonNull(owners, "owners");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(action, "action");
        if (keys == null || keys.isEmpty()) {
            return Verdict.REFUSED_OWNERSHIP;
        }
        List<OwnerRegistry.OwnershipDomain> ordered = new ArrayList<>(keys);
        ordered.sort(DOMAIN_ORDER);
        Set<OwnerRegistry.OwnershipDomain> distinct = new HashSet<>(ordered);
        if (distinct.size() != ordered.size()) {
            return Verdict.REFUSED_OWNERSHIP;
        }
        List<OwnerToken> tokens = tokens(ordered, versions, holderSite, owners.mintEpoch(), 0L,
            0L);
        for (OwnerToken token : tokens) {
            if (token.expectedVersion() == WriteVersionSlots.NOT_CARRIED) {
                return Verdict.REFUSED_VERSION;
            }
        }
        List<OwnerRegistry.OwnershipDomain> fresh = new ArrayList<>();
        for (OwnerToken token : tokens) {
            if (owners.lookup(token.worldId(), token.level(), token.domainId()).isEmpty()) {
                fresh.add(token.domain());
            }
        }
        if (!owners.acquireAll(tokens)) {
            return Verdict.REFUSED_OWNERSHIP;
        }
        for (OwnerToken token : tokens) {
            long live = versions.carried(token.worldId(), token.level(), token.domainId());
            if (live == WriteVersionSlots.NOT_CARRIED || live != token.expectedVersion()) {
                release(owners, fresh, holderSite);
                return Verdict.REFUSED_VERSION;
            }
        }
        try {
            action.run();
        } finally {
            release(owners, fresh, holderSite);
        }
        return Verdict.ADMITTED;
    }

    private static void release(OwnerRegistry owners, List<OwnerRegistry.OwnershipDomain> domains,
                                String holderSite) {
        for (OwnerRegistry.OwnershipDomain domain : domains) {
            owners.release(domain, holderSite);
        }
    }

    private static OwnerRegistry.OwnershipDomain domain(String worldId, String domainId) {
        Objects.requireNonNull(worldId, "worldId");
        return new OwnerRegistry.OwnershipDomain(worldId, WriteLevel.OBJECT, domainId);
    }

    static int compareRows(EntityRowIdentity left, EntityRowIdentity right) {
        int world = left.worldId().compareTo(right.worldId());
        if (world != 0) {
            return world;
        }
        int high = Long.compare(left.uuidHigh(), right.uuidHigh());
        if (high != 0) {
            return high;
        }
        int low = Long.compare(left.uuidLow(), right.uuidLow());
        if (low != 0) {
            return low;
        }
        int id = Integer.compare(left.entityId(), right.entityId());
        return id != 0 ? id : Long.compare(left.tickCount(), right.tickCount());
    }

    private static final Comparator<OwnerRegistry.OwnershipDomain> DOMAIN_ORDER =
        Comparator.comparing(OwnerRegistry.OwnershipDomain::worldId)
            .thenComparing(domain -> domain.level().name())
            .thenComparing(OwnerRegistry.OwnershipDomain::domainId);
}
