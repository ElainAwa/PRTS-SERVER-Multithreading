/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The registry is the only place a write right comes from. Its mutations are serialized so two
 * holders cannot both win the same parent or child level and a set of demands is installed
 * atomically; lookups stay inexpensive. A right is also bounded in time and in generation: a
 * released or reset generation leaves a tombstone behind, so the same value cannot be installed
 * again, and a token that covers a parent level of a domain cannot be installed for another holder
 * while a child level of the same domain is held. */
public final class OwnerRegistry {

    private final Map<OwnershipDomain, OwnerToken> tokens = new ConcurrentHashMap<>();
    /** The newest retired generation of one domain: no token at or below it is installed again. */
    private final Map<OwnershipDomain, Long> retired = new ConcurrentHashMap<>();
    /** The world incarnation the registry was last told about, one value per world. */
    private final Map<String, Long> worldEpochs = new ConcurrentHashMap<>();
    private final AtomicLong lifecycleGeneration = new AtomicLong(1L);
    private final AtomicLong nextEpoch = new AtomicLong(1L);
    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong reacquired = new AtomicLong();
    private final AtomicLong acquireCalls = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong releaseRefused = new AtomicLong();
    private final AtomicLong releaseCalls = new AtomicLong();
    private final AtomicLong expiredReclaimed = new AtomicLong();
    private final AtomicLong reclaimPasses = new AtomicLong();
    private final AtomicLong doubleHolder = new AtomicLong();
    private final AtomicLong staleAcquisitions = new AtomicLong();
    private final AtomicLong overlapRefusals = new AtomicLong();
    private final AtomicLong lifecycleRetired = new AtomicLong();

    /** Acquires a domain for a holder. A retirement or an overlap with another holder's level of the
     * same domain refuses the acquisition; renewing the installed token of the same holder keeps its
     * generation and only rebinds the plan, the world incarnation and the hold window. */
    public synchronized boolean acquire(OwnerToken token) {
        acquireCalls.incrementAndGet();
        if (token == null) {
            doubleHolder.incrementAndGet();
            return false;
        }
        OwnerToken installed = tokens.get(token.domain());
        if (installed != null) {
            return renew(installed, token);
        }
        Long retiredEpoch = retired.get(token.domain());
        if (retiredEpoch != null && token.epoch() <= retiredEpoch) {
            staleAcquisitions.incrementAndGet();
            return false;
        }
        if (overlapsAnotherLevel(token)) {
            overlapRefusals.incrementAndGet();
            return false;
        }
        installed = tokens.putIfAbsent(token.domain(), token);
        if (installed == null) {
            acquired.incrementAndGet();
            return true;
        }
        return renew(installed, token);
    }

    private boolean renew(OwnerToken installed, OwnerToken token) {
        if (!sameHolder(installed, token)) {
            doubleHolder.incrementAndGet();
            return false;
        }
        reacquired.incrementAndGet();
        if (installed.planSequence() != token.planSequence()
            || installed.worldEpoch() != token.worldEpoch()
            || installed.expectedVersion() != token.expectedVersion()
            || installed.expireTick() != token.expireTick()) {
            // The generation stays; the binding it names moves with the plan that renewed it.
            tokens.replace(token.domain(), installed, token);
        }
        return true;
    }


    /** Acquires a set of demands all or none. Every candidate is checked against the installed
     * rights and the other candidates before any of them is installed, so a reader either sees the
     * whole set or none of it. */
    public synchronized boolean acquireAll(List<OwnerToken> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        acquireCalls.addAndGet(candidates.size());
        Set<OwnershipDomain> seen = new HashSet<>();
        for (OwnerToken candidate : candidates) {
            if (candidate == null) {
                doubleHolder.addAndGet(candidates.size());
                return false;
            }
            if (!seen.add(candidate.domain())) {
                overlapRefusals.addAndGet(candidates.size());
                return false;
            }
            OwnerToken installed = tokens.get(candidate.domain());
            if (installed != null) {
                if (!sameHolder(installed, candidate)) {
                    doubleHolder.addAndGet(candidates.size());
                    return false;
                }
                continue;
            }
            Long retiredEpoch = retired.get(candidate.domain());
            if (retiredEpoch != null && candidate.epoch() <= retiredEpoch) {
                staleAcquisitions.addAndGet(candidates.size());
                return false;
            }
            if (overlapsAnotherLevel(candidate) || overlapsCandidate(candidate, candidates)) {
                overlapRefusals.addAndGet(candidates.size());
                return false;
            }
        }
        for (OwnerToken candidate : candidates) {
            OwnerToken installed = tokens.get(candidate.domain());
            if (installed != null) {
                renew(installed, candidate);
            } else {
                tokens.put(candidate.domain(), candidate);
                acquired.incrementAndGet();
            }
        }
        return true;
    }

    private boolean overlapsCandidate(OwnerToken candidate, List<OwnerToken> candidates) {
        for (OwnerToken other : candidates) {
            if (other == null || other == candidate
                || !other.worldId().equals(candidate.worldId())
                || !other.domainId().equals(candidate.domainId())
                || other.level() == candidate.level()
                || sameHolderSite(other, candidate)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Releases a domain, but only for the holder that owns it. */
    public synchronized boolean release(OwnershipDomain domain, String siteId) {
        releaseCalls.incrementAndGet();
        OwnerToken current = tokens.get(domain);
        if (current == null || !current.holderSiteId().equals(siteId)) {
            releaseRefused.incrementAndGet();
            return false;
        }
        if (tokens.remove(domain, current)) {
            retire(domain, current.epoch());
            released.incrementAndGet();
            return true;
        }
        releaseRefused.incrementAndGet();
        return false;
    }

    /** Looks up the token of a domain. */
    public synchronized Optional<OwnerToken> lookup(String worldId, WriteLevel level, String domainId) {
        return Optional.ofNullable(tokens.get(new OwnershipDomain(worldId, level, domainId)));
    }

    /** Reclaims every token whose expiry tick has passed. */
    public synchronized int reclaimExpired(long tickIndex) {
        reclaimPasses.incrementAndGet();
        int reclaimed = 0;
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : tokens.entrySet()) {
            if (!entry.getValue().expiredAt(tickIndex)) {
                continue;
            }
            if (tokens.remove(entry.getKey(), entry.getValue())) {
                retire(entry.getKey(), entry.getValue().epoch());
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            expiredReclaimed.addAndGet(reclaimed);
        }
        return reclaimed;
    }

    /** Records the incarnation of one world. A change of incarnation retires every right of that
     * world before the new one is served, so a token of the world that was replaced cannot grant. */
    public synchronized void noteWorldEpoch(String worldId, long epoch) {
        if (worldId == null || epoch <= 0L) {
            return;
        }
        Long previous = worldEpochs.put(worldId, epoch);
        if (previous != null && previous != epoch) {
            retireWorld(worldId);
        }
    }

    /** The incarnation of one world the registry was last told about, or zero while untracked. */
    public long worldEpochOf(String worldId) {
        Long epoch = worldId == null ? null : worldEpochs.get(worldId);
        return epoch == null ? 0L : epoch;
    }

    /** Ends one registry lifecycle: every installed right is retired, the world incarnations are
     * forgotten and the generation advances, so no value from before the reset can be installed or
     * matched afterwards. */
    public synchronized void resetLifecycle() {
        int removed = 0;
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : tokens.entrySet()) {
            if (tokens.remove(entry.getKey(), entry.getValue())) {
                retire(entry.getKey(), entry.getValue().epoch());
                removed++;
            }
        }
        tokens.clear();
        worldEpochs.clear();
        lifecycleGeneration.incrementAndGet();
        if (removed > 0) {
            lifecycleRetired.addAndGet(removed);
        }
    }

    public synchronized int activeTokens() {
        return tokens.size();
    }

    public long lifecycleGeneration() {
        return lifecycleGeneration.get();
    }

    /** Mints one authorization generation. Every issuer draws from this one counter, so two issuers
     * cannot hand out the same value. */
    public long mintEpoch() {
        return nextEpoch.getAndIncrement();
    }

    public synchronized Map<OwnershipDomain, OwnerToken> snapshot() {
        return Map.copyOf(tokens);
    }

    /** How often a domain was asked for; every acquisition leaves by one of the countable cells. */
    public long acquireCalls() {
        return acquireCalls.get();
    }

    public long releaseCalls() {
        return releaseCalls.get();
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

    /** How often a retired generation was asked for again. */
    public long staleAcquisitionCount() {
        return staleAcquisitions.get();
    }

    /** How often a parent and a child level of one domain were asked for by two holders. */
    public long overlapRefusalCount() {
        return overlapRefusals.get();
    }

    /** How many rights ended with a lifecycle reset rather than a release or an expiry. */
    public long lifecycleRetiredCount() {
        return lifecycleRetired.get();
    }

    /** How often a holder that already owned the domain asked for it again. */
    public long reacquiredCount() {
        return reacquired.get();
    }

    /** How often a release did not name the holder the domain is registered to. */
    public long releaseRefusedCount() {
        return releaseRefused.get();
    }

    /** The write right balance: every acquisition leaves by exactly one of the countable cells, every
     * release by one of the two, and a token is active exactly while it was acquired and neither
     * released, reclaimed nor retired by a lifecycle reset. A reading of the comparisons says
     * whether the registry closed. */
    public boolean conservationHolds() {
        return acquireCalls.get() == acquired.get() + reacquired.get() + doubleHolder.get()
            + staleAcquisitions.get() + overlapRefusals.get()
            && releaseCalls.get() == released.get() + releaseRefused.get()
            && activeTokens() == acquired.get() - released.get() - expiredReclaimed.get()
                - lifecycleRetired.get();
    }

    private boolean overlapsAnotherLevel(OwnerToken candidate) {
        for (OwnerToken installed : tokens.values()) {
            if (!installed.worldId().equals(candidate.worldId())
                || !installed.domainId().equals(candidate.domainId())
                || installed.level() == candidate.level()
                || sameHolderSite(installed, candidate)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void retireWorld(String worldId) {
        int removed = 0;
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : tokens.entrySet()) {
            if (!entry.getKey().worldId().equals(worldId)) {
                continue;
            }
            if (tokens.remove(entry.getKey(), entry.getValue())) {
                retire(entry.getKey(), entry.getValue().epoch());
                removed++;
            }
        }
        if (removed > 0) {
            lifecycleRetired.addAndGet(removed);
        }
    }

    private void retire(OwnershipDomain domain, long epoch) {
        retired.merge(domain, epoch, Math::max);
    }

    private static boolean sameHolder(OwnerToken installed, OwnerToken candidate) {
        return sameHolderSite(installed, candidate) && installed.epoch() == candidate.epoch();
    }

    private static boolean sameHolderSite(OwnerToken installed, OwnerToken candidate) {
        return installed.holderKind() == candidate.holderKind()
            && installed.holderSiteId().equals(candidate.holderSiteId());
    }

    /** The key of one write right domain. A right is always held for one world and one level of that
     * world, so the pair plus the identity inside the level is the key the owner registry stores a
     * token under. */
    public record OwnershipDomain(String worldId, WriteLevel level, String domainId) {
    }
}
