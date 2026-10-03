/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints.observe;

import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** The call sites themselves know only the seam, so this is where the site numbers become the
 * written-down identities and the wait points that cover them: the row identity and the call site
 * reference are resolved once, when the observer is built, and the recording path is then a
 * counter update, one registry observation and one span. */
public final class WaitSiteObserver implements PrtsWaitSites.SiteWaitTap {

    private final WaitPointRegistry registry;
    private final WaitSiteReadings readings;
    private final LongSupplier tickIndex;
    private final String[] waitPointIds;
    private final String[] callSiteRefs;
    private final boolean[] convergenceCandidates;

    /** Creates the observer for every site the seam numbers. */
    public WaitSiteObserver(WaitPointRegistry registry, LongSupplier tickIndex, IntSupplier boundMs) {
        this.registry = registry;
        this.tickIndex = tickIndex;
        this.readings = new WaitSiteReadings(boundMs);
        this.waitPointIds = new String[PrtsWaitSites.SITE_IDS.length];
        this.callSiteRefs = new String[PrtsWaitSites.SITE_IDS.length];
        this.convergenceCandidates = new boolean[PrtsWaitSites.SITE_IDS.length];
        for (int index = 0; index < waitPointIds.length; index++) {
            WaitSite site = registry.sites().lookup(PrtsWaitSites.SITE_IDS[index]);
            if (site == null) {
                // A call site the written-down list does not hold stays unregistered: the
                // observation is recorded and listed, never refused.
                waitPointIds[index] = null;
                callSiteRefs[index] = PrtsWaitSites.SITE_IDS[index];
                continue;
            }
            waitPointIds[index] = site.wpId();
            callSiteRefs[index] = site.classRef() + "#" + site.methodRef();
            convergenceCandidates[index] =
                SiteInventory.FORCED_MATERIALIZATION.equals(site.timeoutAction());
        }
    }

    @Override
    public void observed(int siteIndex, String siteId, String worldId, long waitNanos) {
        if (siteIndex < 0 || siteIndex >= waitPointIds.length) {
            return;
        }
        long waitMs = Math.max(0L, waitNanos / 1_000_000L);
        readings.noteNanos(siteIndex, waitNanos);
        readings.note(siteIndex, waitMs, convergenceCandidates[siteIndex]);
        registry.observeWait(waitPointIds[siteIndex],
            new WaitSpan(waitPointIds[siteIndex], callSiteRefs[siteIndex], siteId,
                worldId == null ? "" : worldId, tickIndex.getAsLong(), waitMs, null));
    }

    /** Installs this observer as the watcher behind the seam. */
    public void attach() {
        PrtsWaitSites.install(this);
    }

    /** Removes this observer from the seam. */
    public void detach() {
        PrtsWaitSites.install(null);
    }

    public WaitSiteReadings readings() {
        return readings;
    }

    /** Clears the per-site readings. */
    public void reset() {
        readings.reset();
    }

    /** Returns the call site reference one site was resolved to. */
    public String callSiteRef(int siteIndex) {
        return siteIndex >= 0 && siteIndex < callSiteRefs.length ? callSiteRefs[siteIndex] : null;
    }

    /** Returns the wait point row one site was resolved to. */
    public String waitPointId(int siteIndex) {
        return siteIndex >= 0 && siteIndex < waitPointIds.length ? waitPointIds[siteIndex] : null;
    }
}
