/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The pre-write admission of one entity move: the row's state key, the section memberships it
 * touches and the directory key an absent or emptied section needs are acquired all or none in one
 * stable key order, the version slots are read again at the write point, and only an admitted move
 * reaches its action. Every refusal leaves the registry exactly as it was and the action unrun, so
 * a row whose move is refused keeps its position.
 */
final class EntityMoveAuthority {

    /** What one admission did; only {@link #ADMITTED} let the action run. */
    enum Verdict {
        ADMITTED,
        REFUSED_VERSION,
        REFUSED_OWNERSHIP
    }

    /** Who asks for the rights and under which generations; the holder is the only site that may
     * release what this call acquired. */
    record Binding(String holderSiteId, HolderKind holderKind, long epoch, long planSequence,
                   long worldEpoch, long holdStartTick, long expireTick) {

        Binding {
            Objects.requireNonNull(holderSiteId, "holderSiteId");
            if (holderSiteId.isEmpty()) {
                throw new IllegalArgumentException("an owner site cannot be empty");
            }
            Objects.requireNonNull(holderKind, "holderKind");
            if (holderKind == HolderKind.UNREGISTERED) {
                throw new IllegalArgumentException("an unregistered holder cannot ask for a right");
            }
            if (epoch < 0L || planSequence < 0L || worldEpoch < 0L || expireTick < 0L) {
                throw new IllegalArgumentException("a binding cannot name a negative generation");
            }
        }
    }

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final Binding binding;
    private long admitted;
    private long refusedVersion;
    private long refusedOwnership;

    EntityMoveAuthority(OwnerRegistry owners, WriteVersionSlots versions, Binding binding) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    /** Admits one move's resources and runs its action only when every resource was admitted. A
     * binding that names no epoch draws a fresh authorization generation for this admission, so a
     * right retired by the previous move of the same site does not refuse the next one. */
    Verdict move(EntityMoveResources.Move move, Runnable action) {
        long epoch = binding.epoch() > 0L ? binding.epoch() : owners.mintEpoch();
        List<OwnerToken> tokens = EntityMoveResources.tokens(move, versions, binding.holderSiteId(),
            binding.holderKind(), epoch, binding.planSequence(), binding.worldEpoch(),
            binding.holdStartTick(), binding.expireTick());
        return admit(tokens, action);
    }

    /**
     * Admits the given resources, in the stable order the registry requires. The slots are read again
     * right before the action; a version that moved between the freeze and the write releases what
     * this call acquired and refuses without running the action.
     */
    Verdict admit(List<OwnerToken> tokens, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (tokens == null || tokens.isEmpty()) {
            refusedOwnership++;
            return Verdict.REFUSED_OWNERSHIP;
        }
        List<OwnerToken> ordered = new ArrayList<>(tokens);
        ordered.sort(EntityMoveResources.tokenOrder());
        Set<OwnerRegistry.OwnershipDomain> distinct = new HashSet<>();
        for (OwnerToken token : ordered) {
            if (!distinct.add(token.domain())) {
                // One key asked twice is not a stable set: the registry refuses it, and so do we,
                // before anything is installed.
                refusedOwnership++;
                return Verdict.REFUSED_OWNERSHIP;
            }
        }
        if (!EntityMoveResources.versionsCarried(ordered)) {
            refusedVersion++;
            return Verdict.REFUSED_VERSION;
        }
        List<OwnerRegistry.OwnershipDomain> fresh = new ArrayList<>();
        for (OwnerToken token : ordered) {
            if (owners.lookup(token.worldId(), token.level(), token.domainId()).isEmpty()) {
                fresh.add(token.domain());
            }
        }
        if (!owners.acquireAll(ordered)) {
            refusedOwnership++;
            return Verdict.REFUSED_OWNERSHIP;
        }
        for (OwnerToken token : ordered) {
            long live = versions.carried(token.worldId(), token.level(), token.domainId());
            if (live == WriteVersionSlots.NOT_CARRIED || live != token.expectedVersion()) {
                release(fresh);
                refusedVersion++;
                return Verdict.REFUSED_VERSION;
            }
        }
        try {
            action.run();
        } finally {
            release(fresh);
        }
        admitted++;
        return Verdict.ADMITTED;
    }

    private void release(List<OwnerRegistry.OwnershipDomain> domains) {
        for (OwnerRegistry.OwnershipDomain domain : domains) {
            owners.release(domain, binding.holderSiteId());
        }
    }

    long admittedCount() {
        return admitted;
    }

    long refusedVersionCount() {
        return refusedVersion;
    }

    long refusedOwnershipCount() {
        return refusedOwnership;
    }
}
