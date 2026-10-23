/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class EndPlatformFeatureEventGuardTest {

    @Test
    void portalCreateReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody("void arclight$portalCreate");
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new PortalCreateEvent");
        assertTrue(accessor >= 0, "end platform reads the world without throwing");
        assertTrue(event > accessor, "the event is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "end platform does not call the strict accessor");
    }

    @Test
    void missingWorldUpdatesBlocksBeforeThePortalEvent() throws Exception {
        String body = methodBody("void arclight$portalCreate");
        int guard = body.indexOf("bworld == null");
        int update = body.indexOf("blockList.updateList()");
        int event = body.indexOf("new PortalCreateEvent");
        assertTrue(guard >= 0 && update > guard && event > update,
            "a missing world applies the captured blocks before any portal event");
    }

    private static String methodBody(String signature) throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/levelgen/feature/EndPlatformFeatureMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/levelgen/feature/EndPlatformFeatureMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf(signature);
        assertTrue(start >= 0, signature);
        int next = text.indexOf("private static void arclight$dropItems", start);
        assertTrue(next > start);
        return text.substring(start, next);
    }
}
