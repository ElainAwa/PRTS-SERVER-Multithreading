/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.be;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The resource keys one BE update holds: BE_STATE, HOST_BLOCK, INVENTORY_SLOT, FLUID_TANK and
 * ENERGY_BUFFER are acquired all or none in a stable order, the host block revision and the BE
 * lifecycle generation are re-read at the write point, and a version move refuses the write and
 * releases every acquired right instead of leaving a partial hold. */
public final class BlockEntityResources {

    /** The acquired rights of one BE update. */
    public record Hold(List<OwnerRegistry.OwnershipDomain> domains) {
    }

    /** What one acquisition did. */
    public record Acquired(Hold hold, boolean granted, String reason) {
    }

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private long refused;
    private long versionRefused;

    public BlockEntityResources(OwnerRegistry owners, WriteVersionSlots versions) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.versions = Objects.requireNonNull(versions, "versions");
    }

    /** The stable resource keys of one BE; a chunk name never splits a shared device network. */
    public static List<OwnerRegistry.OwnershipDomain> keys(BlockEntityIdentity identity) {
        List<OwnerRegistry.OwnershipDomain> keys = new ArrayList<>(5);
        keys.add(domain(identity.worldId(), "be-state:" + identity.blockPosLong() + ":"
            + identity.beLifecycleGeneration()));
        keys.add(domain(identity.worldId(), "be-host-block:" + identity.blockPosLong() + ":"
            + identity.hostBlockRevision()));
        keys.add(domain(identity.worldId(), "be-inventory:" + identity.blockPosLong()));
        keys.add(domain(identity.worldId(), "be-fluid:" + identity.blockPosLong()));
        keys.add(domain(identity.worldId(), "be-energy:" + identity.blockPosLong()));
        return List.copyOf(keys);
    }

    /** Acquires every resource key in stable order; a moved version releases the partial hold. */
    public synchronized Acquired acquire(BlockEntityIdentity identity, String holderSite) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(holderSite, "holderSite");
        List<OwnerRegistry.OwnershipDomain> domains = new ArrayList<>(keys(identity));
        domains.sort(Comparator.comparing(OwnerRegistry.OwnershipDomain::worldId)
            .thenComparing(OwnerRegistry.OwnershipDomain::domainId));
        List<OwnerToken> tokens = new ArrayList<>(domains.size());
        for (OwnerRegistry.OwnershipDomain domain : domains) {
            long version = versions.grant(domain.worldId(), domain.level(), domain.domainId());
            tokens.add(new OwnerToken(domain.worldId(), domain.level(), domain.domainId(), 1L,
                version, 0L, 0L, io.izzel.arclight.common.prts.kernel.auth.HolderKind.REGISTERED,
                holderSite));
        }
        if (!owners.acquireAll(tokens)) {
            refused++;
            return new Acquired(null, false, "refused-ownership");
        }
        for (OwnerToken token : tokens) {
            if (versions.carried(token.worldId(), token.level(), token.domainId())
                != token.expectedVersion()) {
                release(new Hold(List.copyOf(domains)), holderSite);
                versionRefused++;
                return new Acquired(null, false, "refused-version");
            }
        }
        return new Acquired(new Hold(List.copyOf(domains)), true, "acquired");
    }

    /** Releases every right of one hold. */
    public synchronized void release(Hold hold, String holderSite) {
        Objects.requireNonNull(hold, "hold");
        for (OwnerRegistry.OwnershipDomain domain : hold.domains()) {
            owners.release(domain, holderSite);
        }
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long versionRefused() {
        return versionRefused;
    }

    private static OwnerRegistry.OwnershipDomain domain(String worldId, String domainId) {
        return new OwnerRegistry.OwnershipDomain(worldId, WriteLevel.OBJECT, domainId);
    }
}
