/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints.observe;

import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The waits the real call sites produce, and what the readings make of them. */
class WaitSiteObserverTest {

    private static final long TICK = 40L;

    @Test
    void everyObservedSiteIsResolvedToItsRowAndItsCallSite() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> TICK, () -> 50);

        assertEquals(PrtsWaitSites.SITE_IDS.length, observer.readings().siteCount());
        for (int index = 0; index < PrtsWaitSites.SITE_IDS.length; index++) {
            WaitSite site = registry.sites().lookup(PrtsWaitSites.SITE_IDS[index]);
            assertNotNull(site, PrtsWaitSites.SITE_IDS[index]);
            assertEquals(site.wpId(), observer.waitPointId(index));
            assertEquals(site.classRef() + "#" + site.methodRef(), observer.callSiteRef(index));
        }
        assertEquals(0, registry.reportCoverage().siteUnregistered());
    }

    @Test
    void anObservedWaitReachesTheRegistryUnderItsOwnSite() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> TICK, () -> 50);

        for (int index = 0; index < PrtsWaitSites.SITE_IDS.length; index++) {
            observer.observed(index, PrtsWaitSites.SITE_IDS[index], "world", 5_000_000L);
        }

        long summed = 0L;
        for (int index = 0; index < PrtsWaitSites.SITE_IDS.length; index++) {
            assertEquals(1L, observer.readings().observed(index),
                PrtsWaitSites.SITE_IDS[index]);
            summed += observer.readings().observed(index);
        }
        assertEquals(PrtsWaitSites.SITE_IDS.length, summed);
        assertEquals(summed, registry.observationCount());
        assertEquals(0, registry.reportCoverage().siteUnregistered());
        assertEquals(0L, registry.waitOverrunCount());
    }

    @Test
    void aWaitOverOneTickIsCountedAndTheBoundIsNotMoved() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> TICK, () -> 50);
        String site = PrtsWaitSites.SITE_IDS[PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED];

        observer.observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED, site, "world", 60_000_000L);
        observer.observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED, site, "world", 50_000_000L);
        observer.observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED, site, "world", 10_000_000L);

        assertEquals(3L, observer.readings().observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
        assertEquals(1L, observer.readings().overOneTick(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
        assertEquals(1L, observer.readings()
            .convergenceCandidates(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
        assertEquals(1L, observer.readings().overOneTickTotal());
        assertEquals(1L, observer.readings().convergenceCandidateTotal());
        assertEquals(60L, observer.readings().maxMs());
        // The millisecond verdict truncates, so a site whose calls stay under a millisecond would
        // report a count with no time beside it; the exact duration is kept for that case.
        assertEquals(120_000_000L,
            observer.readings().observedNanos(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
        assertEquals(1L, registry.waitOverrunCount());
        assertNotNull(registry.lookup("chunk"));
        assertEquals(SiteInventory.FORCED_MATERIALIZATION,
            registry.sites().lookup(site).timeoutAction());
        assertEquals(60L, observer.readings()
            .maxMs(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
    }

    @Test
    void aSiteThatNeverWaitedIsStillPublishedAsZero() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> TICK, () -> 50);

        assertEquals(PrtsWaitSites.SITE_IDS.length, observer.readings().siteCount());
        assertEquals(0L, observer.readings().observedTotal());
        assertEquals(0L, observer.readings().overOneTickTotal());
        assertEquals(0L, observer.readings().convergenceCandidateTotal());
        assertEquals(0L, observer.readings().maxMs());
        for (int index = 0; index < observer.readings().siteCount(); index++) {
            assertEquals(0L, observer.readings().observed(index));
            assertEquals(0L, observer.readings().observedNanos(index));
            assertEquals(0L, observer.readings().maxMs(index));
            assertEquals(0L, observer.readings().overOneTick(index));
            assertEquals(0L, observer.readings().convergenceCandidates(index));
            assertFalse(observer.readings().siteIds()[index].isBlank());
        }
        assertEquals(0L, registry.observationCount());
    }

    @Test
    void theWatcherIsOnlyInstalledWhileItIsAttached() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> TICK, () -> 50);

        assertFalse(PrtsWaitSites.installed());
        observer.attach();
        assertTrue(PrtsWaitSites.installed());
        PrtsWaitSites.begin(PrtsWaitSites.MAP_ITEM_UPDATE);
        PrtsWaitSites.end(PrtsWaitSites.MAP_ITEM_UPDATE);
        observer.detach();

        assertFalse(PrtsWaitSites.installed());
        assertEquals(1L, observer.readings().observed(PrtsWaitSites.MAP_ITEM_UPDATE));
        assertEquals(1L, registry.observationCount());
    }
}
