/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeBlockPhysicsSyntheticLevelTest {

    @Test
    void aMissingCraftWorldDoesNotCancelTheBlockUpdate() {
        assertEquals("continue", outcome(null, true));
    }

    @Test
    void aCancelledEventOnARealWorldStopsTheUpdate() {
        assertEquals("cancelled", outcome(new Object(), true));
    }

    @Test
    void anEventThatIsNotCancelledLeavesTheUpdateRunning() {
        assertEquals("continue", outcome(new Object(), false));
    }

    @Test
    void theNeoForgePhysicsReadUsesTheNullSafeWorld() throws IOException {
        String source = Files.readString(source());
        assertTrue(source.contains("this.bridge$getWorldOrNull() != null"));
        assertFalse(source.contains("bridge$getWorld()"));
    }

    private static String outcome(Object craftWorld, boolean cancelled) {
        Object world = PrtsSyntheticLevelWorld.orNull(craftWorld);
        if (world != null && cancelled) {
            return "cancelled";
        }
        return "continue";
    }

    private static Path source() {
        Path repo = Paths.get("arclight-neoforge/src/main/java/io/izzel/arclight/neoforge/mixin/core/world/level/LevelMixin_NeoForge.java");
        if (Files.isRegularFile(repo)) {
            return repo;
        }
        return Paths.get("../arclight-neoforge/src/main/java/io/izzel/arclight/neoforge/mixin/core/world/level/LevelMixin_NeoForge.java");
    }
}
