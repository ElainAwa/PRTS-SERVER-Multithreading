/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The registry is the only place a write right comes from, and it is deliberately lock free: a
 * token is a value, and a second holder for the same domain is refused by compare-and-set instead
 * of being serialized by a lock. */
public final class OwnerRegistry {

    private final Map<OwnershipDomain, OwnerToken> tokens = new ConcurrentHashMap<>();
    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong expiredReclaimed = new AtomicLong();
    private final AtomicLong reclaimPasses = new AtomicLong();
    private final AtomicLong doubleHolder = new AtomicLong();

    /** Acquires a domain for a holder. */
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

    /** Releases a domain, but only for the holder that owns it. */
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

    /** Looks up the token of a domain. */
    public Optional<OwnerToken> lookup(String worldId, WriteLevel level, String domainId) {
        return Optional.ofNullable(tokens.get(new OwnershipDomain(worldId, level, domainId)));
    }

    /** Reclaims every token whose expiry tick has passed. */
    public int reclaimExpired(long tickIndex) {
        reclaimPasses.incrementAndGet();
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

    public int activeTokens() {
        return tokens.size();
    }

    public Map<OwnershipDomain, OwnerToken> snapshot() {
        return Map.copyOf(tokens);
    }

    public long acquiredCount() {
        return acquired.get();
    }

    public long releasedCount() {
        return released.get();
    }

    public long expiredReclaimedCount() {
        return expiredReclaimed.get();
    }

    /** Returns how often the expiry sweep ran. Reclaiming belongs to the write-right lifecycle and
     * not to the observation of it, so this count keeps moving while the metering switches are
     * off; a readout that shows it standing still is showing that the kernel is not being driven
     * at all. */
    public long reclaimPasses() {
        return reclaimPasses.get();
    }

    public long doubleHolderCount() {
        return doubleHolder.get();
    }

    private static boolean sameHolder(OwnerToken installed, OwnerToken candidate) {
        return installed.holderSiteId().equals(candidate.holderSiteId())
            && installed.epoch() == candidate.epoch();
    }

    /** The key of one write right domain. A right is always held for one world and one level of that
     * world, so the pair plus the identity inside the level is the key the owner registry stores a
     * token under. */
    public record OwnershipDomain(String worldId, WriteLevel level, String domainId) {
    }
}
