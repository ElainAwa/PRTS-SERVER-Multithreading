/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrtsSyntheticLevelWorldTest {

    private static final String MISSING = "World minecraft:overworld does not have a CraftWorld. "
        + "This method should not be invoked on this world for any reason.";

    @Test
    void anAbsentCraftWorldReadsAsNull() {
        assertNull(PrtsSyntheticLevelWorld.orNull(null));
    }

    @Test
    void aPresentCraftWorldIsReturnedUnchanged() {
        Object world = new Object();
        assertSame(world, PrtsSyntheticLevelWorld.orNull(world));
    }

    @Test
    void requiringAnAbsentCraftWorldStillThrows() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
            () -> PrtsSyntheticLevelWorld.require(null, "minecraft:overworld"));
        assertEquals(MISSING, thrown.getMessage());
    }

    @Test
    void theLevelReadUsesTheSameTwoAnswers() throws IOException {
        String source = Files.readString(levelMixin());
        assertTrue(source.contains("PrtsSyntheticLevelWorld.require(this.world, String.valueOf(dimension().location()))"));
        assertTrue(source.contains("PrtsSyntheticLevelWorld.orNull(this.world)"));
    }

    private static Path levelMixin() {
        for (String candidate : new String[] {
            "src/main/java/io/izzel/arclight/common/mixin/core/world/level/LevelMixin.java",
            "arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/LevelMixin.java"
        }) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return Paths.get("src/main/java/io/izzel/arclight/common/mixin/core/world/level/LevelMixin.java");
    }
}
