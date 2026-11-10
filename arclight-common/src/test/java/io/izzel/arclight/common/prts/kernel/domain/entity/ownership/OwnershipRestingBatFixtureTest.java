/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dedicated fixture contract: the dedicated fixture contains resting NoAI bats only; the existing
 * ownership whitelist therefore has bat claims and no armor-stand/other-model claims. The actual
 * spawn commands/datapack live with the window harness; this test locks the composition contract. */
class OwnershipRestingBatFixtureTest {

    @Test
    void fixtureClaimsOnlyTheBatSlot() throws Exception {
        Map<String, Integer> counts = loadFixture();
        assertTrue(counts.getOrDefault("minecraft:bat", 0) > 0, "fixture has no resting bats");
        assertEquals(0, counts.getOrDefault("minecraft:armor_stand", 0),
            "the dedicated fixture must not contain armor stands");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getKey().equals("minecraft:bat") || entry.getKey().equals("minecraft:armor_stand")
                || entry.getKey().equals("other_modeled")) {
                continue;
            }
            assertEquals(0, entry.getValue(), "unexpected modeled class in fixture: " + entry.getKey());
        }
        assertEquals(0, counts.getOrDefault("other_modeled", 0),
            "the dedicated fixture must not contain other modeled classes");

        TakeoverWhitelist.reset();
        int bats = counts.get("minecraft:bat");
        for (int i = 0; i < bats; i++) {
            TakeoverWhitelist.noteSeen(TickModels.BAT_SLOT, null);
            TakeoverWhitelist.noteClaimed(TickModels.BAT_SLOT);
        }
        assertTrue(TakeoverWhitelist.claimed(TickModels.BAT_SLOT) > 0, "bat claims are missing");
        assertEquals(0, TakeoverWhitelist.claimed(TickModels.ARMOR_STAND_SLOT),
            "armor stands must not be claimed in the dedicated fixture");
        assertEquals(0, TakeoverWhitelist.claimed(TickModels.MARKER_SLOT));
        assertEquals(0, TakeoverWhitelist.claimed(TickModels.VILLAGER_SLOT));
        assertEquals(0, TakeoverWhitelist.claimed(TickModels.AREA_EFFECT_CLOUD_SLOT));
        assertEquals(0, TakeoverWhitelist.claimed(TickModels.OUTSIDE));
    }

    private static Map<String, Integer> loadFixture() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (InputStream input = OwnershipRestingBatFixtureTest.class.getResourceAsStream(
            "resting-bat-fixture.csv")) {
            assertTrue(input != null, "fixture contract resource is missing");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        counts.put(parts[0], Integer.valueOf(parts[1]));
                    }
                }
            }
        }
        return counts;
    }
}
