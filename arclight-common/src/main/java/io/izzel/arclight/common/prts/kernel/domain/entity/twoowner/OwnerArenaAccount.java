/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * The arena account of two owner sites over one shared ledger: both sites claim their slots from
 * the same ArenaLedger, while the claims, releases and active slots are counted per site so the
 * evidence can tell which owner holds what. The ledger instance is never copied or replaced.
 */
public final class OwnerArenaAccount {

    private final ArenaLedger ledger;
    private final Map<String, SiteCounts> counts = new LinkedHashMap<>();

    private static final class SiteCounts {

        final LongAdder claims = new LongAdder();
        final LongAdder releases = new LongAdder();
        final LongAdder active = new LongAdder();
    }

    /** Opens the account of the given owner sites over the one shared ledger. */
    public OwnerArenaAccount(ArenaLedger ledger, java.util.Collection<OwnerSite> sites) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(sites, "sites");
        for (OwnerSite site : sites) {
            counts.put(site.siteId(), new SiteCounts());
        }
    }

    /** Claims one slot for one owner site; the shared ledger counts it and the site is booked. */
    public ArenaSlot claim(OwnerSite site, long batchId, String worldId, String regionId,
                           int segmentKind, int capacity) {
        SiteCounts siteCounts = countsOf(site);
        ArenaSlot slot = ledger.claim(batchId, worldId, regionId, segmentKind, capacity);
        if (slot != null) {
            siteCounts.claims.increment();
            siteCounts.active.increment();
        }
        return slot;
    }

    /** Releases one slot of one owner site; the shared ledger decides the final release state. */
    public ArenaSlot.Release release(OwnerSite site, ArenaSlot.Lease lease, boolean ownerConfirmed) {
        SiteCounts siteCounts = countsOf(site);
        ArenaSlot.Release release = ledger.release(lease, ownerConfirmed);
        if (release == ArenaSlot.Release.RELEASED) {
            siteCounts.releases.increment();
            siteCounts.active.decrement();
        }
        return release;
    }

    /** One owner site's counts: claims, releases and active slots. */
    public Counts countsOf(String siteId) {
        SiteCounts siteCounts = counts.get(siteId);
        if (siteCounts == null) {
            return new Counts(0L, 0L, 0L);
        }
        return new Counts(siteCounts.claims.sum(), siteCounts.releases.sum(),
            siteCounts.active.sum());
    }

    public long totalActive() {
        long active = 0L;
        for (String siteId : counts.keySet()) {
            active += countsOf(siteId).active();
        }
        return active;
    }

    public ArenaLedger ledger() {
        return ledger;
    }

    /** The counts of one site. */
    public record Counts(long claims, long releases, long active) {
    }

    private SiteCounts countsOf(OwnerSite site) {
        Objects.requireNonNull(site, "site");
        SiteCounts siteCounts = counts.get(site.siteId());
        if (siteCounts == null) {
            throw new IllegalArgumentException("the account does not name the site: " + site);
        }
        return siteCounts;
    }
}
