/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeighborUpdaterSyntheticLevelTest {

    @Test
    void aMissingCraftWorldSkipsTheEventAndStillUpdates() {
        assertEquals(List.of("vanilla"), trace(null, false));
    }

    @Test
    void aPresentCraftWorldFiresTheEventBeforeTheUpdate() {
        assertEquals(List.of("event", "vanilla"), trace(new Object(), false));
    }

    @Test
    void aCancelledEventSkipsTheVanillaUpdate() {
        assertEquals(List.of("event"), trace(new Object(), true));
    }

    @Test
    void theNeighborUpdateReadsTheNullSafeWorld() throws IOException {
        String source = Files.readString(source());
        assertTrue(source.contains("((WorldBridge) level).bridge$getWorldOrNull()"));
        assertFalse(source.contains("bridge$getWorld()"));
    }

    private static List<String> trace(Object craftWorld, boolean cancelled) {
        List<String> steps = new ArrayList<>();
        Object world = PrtsSyntheticLevelWorld.orNull(craftWorld);
        if (world != null) {
            steps.add("event");
            if (cancelled) {
                return steps;
            }
        }
        steps.add("vanilla");
        return steps;
    }

    private static Path source() {
        return resolve("src/main/java/io/izzel/arclight/common/mixin/core/world/level/redstone/NeighborUpdaterMixin.java",
            "arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/redstone/NeighborUpdaterMixin.java");
    }

    private static Path resolve(String moduleRelative, String repoRelative) {
        Path module = Paths.get(moduleRelative);
        if (Files.isRegularFile(module)) {
            return module;
        }
        return Paths.get(repoRelative);
    }
}
