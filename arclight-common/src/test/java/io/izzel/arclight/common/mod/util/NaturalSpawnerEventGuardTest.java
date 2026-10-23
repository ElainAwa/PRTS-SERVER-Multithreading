/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class NaturalSpawnerEventGuardTest {

    @Test
    void spawnLimitReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int limitCall = body.indexOf("getSpawnLimit");
        assertTrue(accessor >= 0 && limitCall > accessor, "the bukkit limit is read only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "spawning does not call the strict accessor");
    }

    @Test
    void missingWorldKeepsTheVanillaCapInSourceOrder() throws Exception {
        String body = methodBody();
        int vanilla = body.indexOf("classification.getMaxInstancesPerChunk()");
        int guard = body.indexOf("bukkitWorld != null");
        int limitCall = body.indexOf("getSpawnLimit");
        assertTrue(vanilla >= 0 && guard > vanilla && limitCall > guard,
            "the vanilla limit is assigned before the bukkit override");
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/NaturalSpawnerMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/NaturalSpawnerMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void spawnForChunk");
        assertTrue(start >= 0);
        int next = text.indexOf("@Inject", start);
        if (next < 0) {
            next = text.length();
        }
        return text.substring(start, next);
    }
}
