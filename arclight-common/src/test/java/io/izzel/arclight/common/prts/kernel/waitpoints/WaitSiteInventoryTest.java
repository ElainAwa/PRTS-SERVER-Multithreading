/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.SiteRegisterResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The written-down list of call sites and the coverage it proves. */
class WaitSiteInventoryTest {

    @Test
    void everyListedSiteCarriesAllFourElementsAndARow() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        CoverageReport coverage = registry.reportCoverage();

        assertEquals(20, coverage.siteInventoryTotal());
        assertEquals(20, coverage.siteRegistered());
        assertEquals(0, coverage.siteUnregistered());
        assertTrue(coverage.siteUncoveredIds().isEmpty());
        assertTrue(coverage.sitePendingElements().isEmpty());
        assertEquals(100.0, coverage.siteCoveragePct());
        assertEquals(20, registry.sites().callSites());
        for (WaitSite site : registry.sites().sites()) {
            assertTrue(site.complete(), site.siteId());
            assertNotNull(registry.lookup(site.wpId()), site.siteId());
            assertFalse(site.classRef().isBlank(), site.siteId());
            assertFalse(site.methodRef().isBlank(), site.siteId());
            assertFalse(site.tickPhase().isBlank(), site.siteId());
            assertFalse(site.shareClass().isBlank(), site.siteId());
        }
    }

    @Test
    void theListedSitesAreDistinctAndNameTheirPhaseAndShareRow() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        Set<String> identities = new HashSet<>();
        Set<String> phases = new HashSet<>();
        Set<String> rows = new HashSet<>();
        for (WaitSite site : registry.sites().sites()) {
            assertTrue(identities.add(site.siteId()), site.siteId());
            phases.add(site.tickPhase());
            rows.add(site.shareClass());
        }

        assertEquals(20, identities.size());
        assertTrue(phases.contains("entity_tick"));
        assertTrue(phases.contains("structure_query"));
        assertTrue(rows.contains("entity"));
        assertTrue(rows.contains("graph"));
        assertEquals("entity_tick", registry.sites().lookup("entity_set_pos_raw").tickPhase());
        assertEquals("graph",
            registry.sites().lookup("structure_manager_has_any_structure_at").shareClass());
    }

    @Test
    void aSiteMissingAnElementIsRefusedAndNamesIt() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        SiteRegisterResult result = registry.registerSite(new WaitSite("incomplete", "chunk",
            "example.Incomplete", "run", "entity_tick", "other", "a producer",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT, ""), "postpone",
            "snapshot", "test", 1));

        assertTrue(result instanceof SiteRegisterResult.MissingElement);
        assertEquals("progress signal",
            ((SiteRegisterResult.MissingElement) result).which());
        assertEquals(20, registry.reportCoverage().siteInventoryTotal());
    }

    @Test
    void aSiteOfferedTwiceIsRefused() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSite listed = registry.sites().lookup("map_item_update");

        SiteRegisterResult result = registry.registerSite(listed);

        assertTrue(result instanceof SiteRegisterResult.DuplicateSiteId);
    }

    @Test
    void aSiteSeenAtRunTimeWithoutARowIsListedNotRefused() {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);

        registry.observeWait("chunk", new WaitSpan("chunk", "unknown.call", "site:unknown",
            "world", 100L, 5L, "3"));
        CoverageReport coverage = registry.reportCoverage();

        assertEquals(1, coverage.siteUnregistered());
        List<String> unknown = registry.sites().observedWithoutRow();
        assertEquals(List.of("site:unknown"), unknown);
        assertEquals(20, coverage.siteInventoryTotal());
    }
}
