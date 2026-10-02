/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;

/** The single credential for writing one domain of one world. A token is valid for a period that
 * starts at a tick and ends at an expiry tick, and it names the version slot the holder expects to
 * find. */
public record OwnerToken(String worldId, WriteLevel level, String domainId, long epoch,
                         long expectedVersion, long holdStartTick, long expireTick,
                         HolderKind holderKind, String holderSiteId) {

    public OwnerToken {
        if (holderKind == HolderKind.UNREGISTERED) {
            throw new IllegalArgumentException("an unregistered holder cannot own a domain");
        }
    }

    /** Reports whether the token has expired at a tick. */
    public boolean expiredAt(long tickIndex) {
        return expireTick > 0L && tickIndex >= expireTick;
    }

    public OwnershipDomain domain() {
        return new OwnershipDomain(worldId, level, domainId);
    }
}
