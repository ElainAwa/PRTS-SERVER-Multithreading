/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints.observe;

import io.izzel.arclight.common.prts.support.PrtsWaitSites;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * What the wait observations of the written-down call sites add up to, per site and in total.
 *
 * <p>Two verdicts travel with every wait beside its duration. One is physical and fixed: did the
 * wait cross a host tick. The other follows the configured upper bound and answers the question the
 * convergence action would ask - a wait that crossed it at a site whose timeout action is a forced
 * materialization would have entered that action, had this build been allowed to run one. Both are
 * recorded here and neither is acted on.</p>
 *
 * <p>The counters are published whole, zero rows included, and are indexed the same way the seam
 * numbers its call sites, so a reader can line the two tables up without a lookup.</p>
 */
public final class WaitSiteReadings {

    /** Length of one host tick in milliseconds; the fixed threshold of the physical verdict. */
    public static final long ONE_TICK_MS = 50L;

    private final String[] siteIds;
    private final IntSupplier boundMs;
    private final LongAdder[] observed;
    private final LongAdder[] overOneTick;
    private final LongAdder[] convergenceCandidates;
    private final AtomicLongArray maxMs;
    private final LongAdder observedTotal = new LongAdder();
    private final LongAdder overOneTickTotal = new LongAdder();
    private final LongAdder convergenceCandidateTotal = new LongAdder();

    /**
     * Creates the readings for every written-down call site.
     *
     * @param boundMs upper bound of one wait in milliseconds, read at every observation so a
     *                configuration reload applies without a restart
     */
    public WaitSiteReadings(IntSupplier boundMs) {
        this.siteIds = PrtsWaitSites.SITE_IDS.clone();
        this.boundMs = boundMs;
        int count = siteIds.length;
        this.observed = adders(count);
        this.overOneTick = adders(count);
        this.convergenceCandidates = adders(count);
        this.maxMs = new AtomicLongArray(count);
    }

    /**
     * Notes one observed wait.
     *
     * @param siteIndex            index of the site in the seam table
     * @param waitMs               duration of the wait in milliseconds
     * @param convergenceCandidate whether the wait point covering the site names a forced
     *                             materialization convergence as its timeout action
     */
    public void note(int siteIndex, long waitMs, boolean convergenceCandidate) {
        if (siteIndex < 0 || siteIndex >= siteIds.length) {
            return;
        }
        long duration = Math.max(0L, waitMs);
        observed[siteIndex].increment();
        observedTotal.increment();
        maxMs.accumulateAndGet(siteIndex, duration, Math::max);
        if (duration > ONE_TICK_MS) {
            overOneTick[siteIndex].increment();
            overOneTickTotal.increment();
        }
        if (convergenceCandidate && duration > boundMs.getAsInt()) {
            convergenceCandidates[siteIndex].increment();
            convergenceCandidateTotal.increment();
        }
    }

    /** Clears every counter. Used by the readout reset and by tests, never by the scheduler. */
    public void reset() {
        for (int index = 0; index < siteIds.length; index++) {
            observed[index].reset();
            overOneTick[index].reset();
            convergenceCandidates[index].reset();
            maxMs.set(index, 0L);
        }
        observedTotal.reset();
        overOneTickTotal.reset();
        convergenceCandidateTotal.reset();
    }

    /** @return identities of the call sites, in the order the seam numbers them */
    public String[] siteIds() {
        return siteIds.clone();
    }

    /** @return how many sites the readings cover */
    public int siteCount() {
        return siteIds.length;
    }

    /**
     * Returns how many waits one site produced.
     *
     * @param siteIndex index of the site
     * @return the number of observed waits, zero when it never waited
     */
    public long observed(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? observed[siteIndex].sum() : 0L;
    }

    /**
     * Returns the longest wait one site produced.
     *
     * @param siteIndex index of the site
     * @return the longest wait in milliseconds, zero when it never waited
     */
    public long maxMs(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? maxMs.get(siteIndex) : 0L;
    }

    /**
     * Returns how many waits of one site crossed a host tick.
     *
     * @param siteIndex index of the site
     * @return the number of waits longer than one tick
     */
    public long overOneTick(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? overOneTick[siteIndex].sum() : 0L;
    }

    /**
     * Returns how many waits of one site would have entered a forced materialization convergence.
     *
     * @param siteIndex index of the site
     * @return the number of waits over the configured upper bound
     */
    public long convergenceCandidates(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length
            ? convergenceCandidates[siteIndex].sum() : 0L;
    }

    /** @return every observed wait, across all sites */
    public long observedTotal() {
        return observedTotal.sum();
    }

    /** @return every wait longer than one host tick, across all sites */
    public long overOneTickTotal() {
        return overOneTickTotal.sum();
    }

    /** @return every wait that crossed the configured upper bound, across all sites */
    public long convergenceCandidateTotal() {
        return convergenceCandidateTotal.sum();
    }

    /** @return the longest wait observed at any site, in milliseconds */
    public long maxMs() {
        long max = 0L;
        for (int index = 0; index < siteIds.length; index++) {
            max = Math.max(max, maxMs.get(index));
        }
        return max;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int index = 0; index < count; index++) {
            adders[index] = new LongAdder();
        }
        return adders;
    }
}
