/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seam the wait call sites report through: inert until watched, and never anything more. */
class PrtsWaitSitesTest {

    @AfterEach
    void removeWatcher() {
        PrtsWaitSites.install(null);
    }

    @Test
    void theSeamIsInertUntilAWatcherIsInstalled() {
        List<String> seen = new ArrayList<>();

        assertFalse(PrtsWaitSites.installed());
        PrtsWaitSites.begin(PrtsWaitSites.ENTITY_SET_POS_RAW);
        PrtsWaitSites.end(PrtsWaitSites.ENTITY_SET_POS_RAW);

        assertTrue(seen.isEmpty());
    }

    @Test
    void anInstalledWatcherSeesOneObservationPerOpenedSite() {
        List<String> seen = new ArrayList<>();
        PrtsWaitSites.install((index, siteId, worldId, waitNanos) -> seen.add(index + ":" + siteId));

        PrtsWaitSites.begin(PrtsWaitSites.MAP_ITEM_UPDATE);
        PrtsWaitSites.end(PrtsWaitSites.MAP_ITEM_UPDATE);

        assertTrue(PrtsWaitSites.installed());
        assertEquals(List.of(PrtsWaitSites.MAP_ITEM_UPDATE + ":map_item_update"), seen);
    }

    @Test
    void closingASiteThatWasNeverOpenedOrClosingItTwiceRecordsNothing() {
        List<String> seen = new ArrayList<>();
        PrtsWaitSites.install((index, siteId, worldId, waitNanos) -> seen.add(siteId));

        PrtsWaitSites.end(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);
        PrtsWaitSites.begin(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);
        PrtsWaitSites.end(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);
        PrtsWaitSites.end(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);

        assertEquals(List.of("chunk_map_resend_biomes_for_chunks"), seen);
    }

    @Test
    void everyNumberOfTheSeamNamesItsOwnSite() {
        List<String> seen = new ArrayList<>();
        PrtsWaitSites.install((index, siteId, worldId, waitNanos) -> seen.add(index + "=" + siteId));

        for (int index = 0; index < PrtsWaitSites.SITE_IDS.length; index++) {
            PrtsWaitSites.begin(index);
            PrtsWaitSites.end(index);
        }

        assertEquals(PrtsWaitSites.SITE_IDS.length, seen.size());
        for (int index = 0; index < PrtsWaitSites.SITE_IDS.length; index++) {
            assertEquals(index + "=" + PrtsWaitSites.SITE_IDS[index], seen.get(index));
        }
    }

    @Test
    void aSiteWithNoLevelStillReportsItsWait() {
        List<String> worlds = new ArrayList<>();
        PrtsWaitSites.install((index, siteId, worldId, waitNanos) -> worlds.add(worldId));

        PrtsWaitSites.begin(PrtsWaitSites.MAP_ITEM_UPDATE);
        PrtsWaitSites.end(PrtsWaitSites.MAP_ITEM_UPDATE, new Object());

        assertEquals(List.of(""), worlds);
    }
}
