/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;

/** The single credential for writing one domain of one world; it is valid between its start and
 * expiry ticks and names the authorization generation, plan and world incarnation it was granted
 * under. A token minted without a plan or a tracked world carries the absence of both. */
public record OwnerToken(String worldId, WriteLevel level, String domainId, long epoch,
                         long expectedVersion, long holdStartTick, long expireTick,
                         HolderKind holderKind, String holderSiteId, long planSequence,
                         long worldEpoch) {

    public OwnerToken {
        if (holderKind == HolderKind.UNREGISTERED) {
            throw new IllegalArgumentException("an unregistered holder cannot own a domain");
        }
        if (planSequence < 0L) {
            throw new IllegalArgumentException("a plan binding cannot be negative");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("a world binding cannot be negative");
        }
    }

    /** A token with no plan and no tracked world incarnation: the shape every token had before the
     * two bindings existed. */
    public OwnerToken(String worldId, WriteLevel level, String domainId, long epoch,
                      long expectedVersion, long holdStartTick, long expireTick,
                      HolderKind holderKind, String holderSiteId) {
        this(worldId, level, domainId, epoch, expectedVersion, holdStartTick, expireTick,
            holderKind, holderSiteId, 0L, 0L);
    }

    public boolean expiredAt(long tickIndex) {
        return expireTick > 0L && tickIndex >= expireTick;
    }

    public OwnershipDomain domain() {
        return new OwnershipDomain(worldId, level, domainId);
    }
}
