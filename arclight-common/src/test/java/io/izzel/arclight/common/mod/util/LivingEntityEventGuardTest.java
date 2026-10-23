/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class LivingEntityEventGuardTest {

    @Test
    void teleportReadsBothLocationsWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int from = body.indexOf("new Location(bworld, this.getX()");
        int to = body.indexOf("new Location(bworld, x, y, z)");
        assertTrue(accessor >= 0 && from > accessor && to > from, "both locations use the same non-throwing read");
        assertFalse(body.contains(".bridge$getWorld()"), "the teleport does not call the strict accessor");
    }

    @Test
    void missingWorldStillCallsTheVanillaTeleport() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bworld != null");
        int event = body.indexOf("new EntityTeleportEvent");
        int move = body.indexOf("DecorationOps.callsite().invoke(entity, x, y, z)");
        assertTrue(guard >= 0 && event > guard && move > event,
            "the vanilla teleport stays outside the event and still runs");
    }

    private static String methodBody() throws Exception {
        String text = source();
        int start = text.indexOf("void arclight$entityTeleport");
        int end = text.indexOf("void arclight$foodEffectCause");
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/entity/LivingEntityMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/entity/LivingEntityMixin.java");
        }
        return Files.readString(path);
    }
}
