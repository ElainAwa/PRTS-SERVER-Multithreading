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

class LevelChunkPopulateSyntheticLevelTest {

    @Test
    void aMissingCraftWorldSkipsPopulators() {
        assertEquals(List.of(), trace(null));
    }

    @Test
    void aPresentCraftWorldRunsPopulators() {
        assertEquals(List.of("populate"), trace(new Object()));
    }

    @Test
    void theChunkLoadReadsTheNullSafeWorld() throws IOException {
        String source = Files.readString(source());
        assertTrue(source.contains("((WorldBridge) this.level).bridge$getWorldOrNull()"));
        assertFalse(source.contains("bridge$getWorld()"));
    }

    private static List<String> trace(Object craftWorld) {
        List<String> steps = new ArrayList<>();
        Object world = PrtsSyntheticLevelWorld.orNull(craftWorld);
        if (world != null) {
            steps.add("populate");
        }
        return steps;
    }

    private static Path source() {
        Path module = Paths.get("src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk/LevelChunkMixin.java");
        if (Files.isRegularFile(module)) {
            return module;
        }
        return Paths.get("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk/LevelChunkMixin.java");
    }
}
