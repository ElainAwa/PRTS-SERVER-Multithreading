/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The owner registry: one valid token per domain at any moment.
 *
 * <p>The registry is the only place a write right comes from, and it is deliberately lock free:
 * a token is a value, and a second holder for the same domain is refused by compare-and-set instead
 * of being serialized by a lock. The counters next to it are readouts; nothing in a write path
 * depends on them.</p>
 *
 * <p>A token is never replaced silently. Acquiring a domain that already carries a valid token for
 * a different holder counts a zero-tolerance violation and leaves the register untouched; expired
 * tokens are reclaimed together and counted.</p>
 */
public final class OwnerRegistry {

    private final Map<OwnershipDomain, OwnerToken> tokens = new ConcurrentHashMap<>();
    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong expiredReclaimed = new AtomicLong();
    private final AtomicLong doubleHolder = new AtomicLong();

    /**
     * Acquires a domain for a holder.
     *
     * @param token the token to install
     * @return {@code true} when the register now carries this token; {@code false} when another
     *         valid holder owns the domain, which is counted as a violation
     */
    public boolean acquire(OwnerToken token) {
        OwnerToken installed = tokens.putIfAbsent(token.domain(), token);
        if (installed == null) {
            acquired.incrementAndGet();
            return true;
        }
        if (sameHolder(installed, token)) {
            return true;
        }
        doubleHolder.incrementAndGet();
        return false;
    }

    /**
     * Releases a domain, but only for the holder that owns it.
     *
     * @param domain the domain
     * @param siteId the site that releases
     * @return {@code true} when the register was cleared
     */
    public boolean release(OwnershipDomain domain, String siteId) {
        OwnerToken current = tokens.get(domain);
        if (current == null || !current.holderSiteId().equals(siteId)) {
            return false;
        }
        if (tokens.remove(domain, current)) {
            released.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Looks up the token of a domain.
     *
     * @param worldId  world of the domain
     * @param level    level of the domain
     * @param domainId identity inside the level
     * @return the valid token, or empty when the domain has none
     */
    public Optional<OwnerToken> lookup(String worldId, WriteLevel level, String domainId) {
        return Optional.ofNullable(tokens.get(new OwnershipDomain(worldId, level, domainId)));
    }

    /**
     * Reclaims every token whose expiry tick has passed.
     *
     * @param tickIndex current tick
     * @return the number of reclaimed tokens, also added to the readout
     */
    public int reclaimExpired(long tickIndex) {
        int reclaimed = 0;
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : tokens.entrySet()) {
            if (!entry.getValue().expiredAt(tickIndex)) {
                continue;
            }
            if (tokens.remove(entry.getKey(), entry.getValue())) {
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            expiredReclaimed.addAndGet(reclaimed);
        }
        return reclaimed;
    }

    /** @return the number of domains that currently carry a token */
    public int activeTokens() {
        return tokens.size();
    }

    /** @return a snapshot of the register, safe to read while tokens change */
    public Map<OwnershipDomain, OwnerToken> snapshot() {
        return Map.copyOf(tokens);
    }

    /** @return tokens granted since the process started */
    public long acquiredCount() {
        return acquired.get();
    }

    /** @return tokens released by their holder since the process started */
    public long releasedCount() {
        return released.get();
    }

    /** @return tokens reclaimed because they expired */
    public long expiredReclaimedCount() {
        return expiredReclaimed.get();
    }

    /** @return acquisitions refused because another holder was still valid; must stay zero */
    public long doubleHolderCount() {
        return doubleHolder.get();
    }

    private static boolean sameHolder(OwnerToken installed, OwnerToken candidate) {
        return installed.holderSiteId().equals(candidate.holderSiteId())
            && installed.epoch() == candidate.epoch();
    }
}
