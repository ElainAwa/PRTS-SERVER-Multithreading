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

class GameEventSyntheticLevelTest {

    @Test
    void aMissingCraftWorldKeepsTheOriginalRadius() {
        assertEquals(List.of("radius:4"), trace(null, false, 4, 9));
    }

    @Test
    void aPresentCraftWorldUsesTheEventRadius() {
        assertEquals(List.of("event", "radius:9"), trace(new Object(), false, 4, 9));
    }

    @Test
    void aCancelledEventStopsDispatch() {
        assertEquals(List.of("event"), trace(new Object(), true, 4, 9));
    }

    @Test
    void theDispatcherReadsTheNullSafeWorld() throws IOException {
        String source = Files.readString(source());
        assertTrue(source.contains("((WorldBridge) this.level).bridge$getWorldOrNull()"));
        assertFalse(source.contains("bridge$getWorld()"));
    }

    private static List<String> trace(Object craftWorld, boolean cancelled, int original, int replaced) {
        List<String> steps = new ArrayList<>();
        Object world = PrtsSyntheticLevelWorld.orNull(craftWorld);
        if (world == null) {
            steps.add("radius:" + original);
            return steps;
        }
        steps.add("event");
        if (cancelled) {
            return steps;
        }
        steps.add("radius:" + replaced);
        return steps;
    }

    private static Path source() {
        Path module = Paths.get("src/main/java/io/izzel/arclight/common/mixin/core/world/level/gameevent/GameEventDispatcherMixin.java");
        if (Files.isRegularFile(module)) {
            return module;
        }
        return Paths.get("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/gameevent/GameEventDispatcherMixin.java");
    }
}
