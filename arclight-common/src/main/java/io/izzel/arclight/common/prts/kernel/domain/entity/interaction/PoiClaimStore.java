/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The occupancy claims of the point-of-interest records: a record has at most one holder, the first
 * claim wins, a second claim is refused with the current holder and a release only succeeds for the
 * holder itself. The store is a per-record map, never a server-wide barrier: claims of different
 * records proceed without waiting for each other.
 */
public final class PoiClaimStore {

    /** What one claim or release did. */
    public record Claim(boolean granted, String recordKey, String holderSite) {

        public Claim {
            Objects.requireNonNull(recordKey, "recordKey");
        }
    }

    private final ConcurrentHashMap<String, String> holders = new ConcurrentHashMap<>();
    private final AtomicLong claims = new AtomicLong();
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong releases = new AtomicLong();

    /** Claims one record for one site; the first site wins and every later site is refused. */
    public Claim claim(String recordKey, String holderSite) {
        Objects.requireNonNull(recordKey, "recordKey");
        Objects.requireNonNull(holderSite, "holderSite");
        String installed = holders.putIfAbsent(recordKey, holderSite);
        if (installed == null) {
            claims.incrementAndGet();
            return new Claim(true, recordKey, holderSite);
        }
        refusals.incrementAndGet();
        return new Claim(false, recordKey, installed);
    }

    /** Releases one record; only its holder may release it. */
    public Claim release(String recordKey, String holderSite) {
        Objects.requireNonNull(recordKey, "recordKey");
        Objects.requireNonNull(holderSite, "holderSite");
        boolean removed = holders.remove(recordKey, holderSite);
        if (removed) {
            releases.incrementAndGet();
        }
        return new Claim(removed, recordKey, holderOf(recordKey));
    }

    /** The current holder of one record, or null when nobody holds it. */
    public String holderOf(String recordKey) {
        return holders.get(recordKey);
    }

    /** Whether one site holds the record. */
    public boolean heldBy(String recordKey, String holderSite) {
        return holderSite.equals(holders.get(recordKey));
    }

    public int heldRecords() {
        return holders.size();
    }

    public long claimedCount() {
        return claims.get();
    }

    public long refusedCount() {
        return refusals.get();
    }

    public long releasedCount() {
        return releases.get();
    }
}
