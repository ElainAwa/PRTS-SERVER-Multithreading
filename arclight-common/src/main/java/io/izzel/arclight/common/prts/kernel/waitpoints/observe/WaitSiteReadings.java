/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints.observe;

import io.izzel.arclight.common.prts.support.PrtsWaitSites;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/** Two verdicts travel with every wait beside its duration. */
public final class WaitSiteReadings {

    /** Length of one host tick in milliseconds; the fixed threshold of the physical verdict. */
    public static final long ONE_TICK_MS = 50L;

    private final String[] siteIds;
    private final IntSupplier boundMs;
    private final LongAdder[] observed;
    private final LongAdder[] observedNanos;
    private final LongAdder[] overOneTick;
    private final LongAdder[] convergenceCandidates;
    private final AtomicLongArray maxMs;
    private final LongAdder observedTotal = new LongAdder();
    private final LongAdder overOneTickTotal = new LongAdder();
    private final LongAdder convergenceCandidateTotal = new LongAdder();

    /** Creates the readings for every written-down call site. */
    public WaitSiteReadings(IntSupplier boundMs) {
        this.siteIds = PrtsWaitSites.SITE_IDS.clone();
        this.boundMs = boundMs;
        int count = siteIds.length;
        this.observed = adders(count);
        this.observedNanos = adders(count);
        this.overOneTick = adders(count);
        this.convergenceCandidates = adders(count);
        this.maxMs = new AtomicLongArray(count);
    }

    /** Keeps the exact duration of one observed wait beside its millisecond verdict: the wait
     * counter truncates to whole milliseconds, so a site whose calls are shorter than one
     * millisecond would otherwise report a count with no time next to it. Observation only. */
    public void noteNanos(int siteIndex, long waitNanos) {
        if (siteIndex < 0 || siteIndex >= siteIds.length || waitNanos <= 0L) {
            return;
        }
        observedNanos[siteIndex].add(waitNanos);
    }

    /** Returns the exact observed duration of one site, in nanoseconds. */
    public long observedNanos(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? observedNanos[siteIndex].sum() : 0L;
    }

    /** Notes one observed wait. */
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

    /** Clears every counter. */
    public void reset() {
        for (int index = 0; index < siteIds.length; index++) {
            observed[index].reset();
            observedNanos[index].reset();
            overOneTick[index].reset();
            convergenceCandidates[index].reset();
            maxMs.set(index, 0L);
        }
        observedTotal.reset();
        overOneTickTotal.reset();
        convergenceCandidateTotal.reset();
    }

    public String[] siteIds() {
        return siteIds.clone();
    }

    public int siteCount() {
        return siteIds.length;
    }

    /** Returns how many waits one site produced. */
    public long observed(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? observed[siteIndex].sum() : 0L;
    }

    /** Returns the longest wait one site produced. */
    public long maxMs(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? maxMs.get(siteIndex) : 0L;
    }

    /** Returns how many waits of one site crossed a host tick. */
    public long overOneTick(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length ? overOneTick[siteIndex].sum() : 0L;
    }

    /** Returns how many waits of one site would have entered a forced materialization convergence.
     */
    public long convergenceCandidates(int siteIndex) {
        return siteIndex >= 0 && siteIndex < siteIds.length
            ? convergenceCandidates[siteIndex].sum() : 0L;
    }

    public long observedTotal() {
        return observedTotal.sum();
    }

    public long overOneTickTotal() {
        return overOneTickTotal.sum();
    }

    public long convergenceCandidateTotal() {
        return convergenceCandidateTotal.sum();
    }

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
