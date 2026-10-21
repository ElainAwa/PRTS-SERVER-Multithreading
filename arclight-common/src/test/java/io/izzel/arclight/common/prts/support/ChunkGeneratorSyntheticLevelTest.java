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

class ChunkGeneratorSyntheticLevelTest {

    @Test
    void aMissingCraftWorldSkipsStructureAndDecorationEvents() {
        assertEquals(List.of("vanilla"), structure(null, false));
        assertEquals(List.of(), decorations(null, true));
    }

    @Test
    void aPresentCraftWorldRunsBothBeforeTheVanillaStep() {
        assertEquals(List.of("event", "vanilla"), structure(new Object(), false));
        assertEquals(List.of("populate"), decorations(new Object(), true));
    }

    @Test
    void aCancelledStructureEventDoesNotContinue() {
        assertEquals(List.of("event"), structure(new Object(), true));
    }

    @Test
    void anEmptyPopulatorListDoesNotPopulate() {
        assertEquals(List.of(), decorations(new Object(), false));
    }

    @Test
    void theGeneratorReadsTheNullSafeWorld() throws IOException {
        String source = Files.readString(source());
        assertTrue(source.contains("((WorldBridge) bridge.bridge$getMinecraftWorld()).bridge$getWorldOrNull()"));
        assertTrue(source.contains("((WorldBridge) region.getLevel()).bridge$getWorldOrNull()"));
        assertFalse(source.contains("bridge$getWorld()"));
    }

    private static List<String> structure(Object craftWorld, boolean cancelled) {
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

    private static List<String> decorations(Object craftWorld, boolean hasPopulators) {
        List<String> steps = new ArrayList<>();
        Object world = PrtsSyntheticLevelWorld.orNull(craftWorld);
        if (world != null && hasPopulators) {
            steps.add("populate");
        }
        return steps;
    }

    private static Path source() {
        Path module = Paths.get("src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk/ChunkGeneratorMixin.java");
        if (Files.isRegularFile(module)) {
            return module;
        }
        return Paths.get("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk/ChunkGeneratorMixin.java");
    }
}
