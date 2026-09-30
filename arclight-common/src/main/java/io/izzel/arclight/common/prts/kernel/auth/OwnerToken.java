/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/**
 * The single credential for writing one domain of one world.
 *
 * <p>A token is valid for a period that starts at a tick and ends at an expiry tick, and it names
 * the version slot the holder expects to find. Possession is the same as the write right: the
 * decision point compares the holder of the token with the writer rather than taking a lock.</p>
 *
 * @param worldId         world the right belongs to
 * @param level           level of the domain
 * @param domainId        identity of the domain inside the level
 * @param epoch           generation of the token; a reclaimed token is never reused
 * @param expectedVersion version slot the holder expects
 * @param holdStartTick   tick the token was granted at
 * @param expireTick      tick the token stops being valid at; zero means no expiry
 * @param holderKind      where the holder comes from
 * @param holderSiteId    site identity of the holder
 */
public record OwnerToken(String worldId, WriteLevel level, String domainId, long epoch,
                         long expectedVersion, long holdStartTick, long expireTick,
                         HolderKind holderKind, String holderSiteId) {

    public OwnerToken {
        if (holderKind == HolderKind.UNREGISTERED) {
            throw new IllegalArgumentException("an unregistered holder cannot own a domain");
        }
    }

    /**
     * Reports whether the token has expired at a tick.
     *
     * @param tickIndex tick to test
     * @return {@code true} when the expiry tick has been reached
     */
    public boolean expiredAt(long tickIndex) {
        return expireTick > 0L && tickIndex >= expireTick;
    }

    /** @return the domain this token is registered under */
    public OwnershipDomain domain() {
        return new OwnershipDomain(worldId, level, domainId);
    }
}
