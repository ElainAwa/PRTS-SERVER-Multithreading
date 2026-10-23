/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityEventGuardTest {

    @Test
    void collisionAndDimensionChangeReadTheWorldWithoutThrowing() throws Exception {
        String text = source();
        String collision = between(text, "void arclight$move$blockCollide", "void arclight$loadChunk");
        String change = between(text, "void arclight$changeDim", "void arclight$skipTeleportIfNotInWorld");
        assertTrue(collision.contains("bridge$getWorldOrNull()"));
        assertTrue(collision.indexOf("bworld == null") < collision.indexOf("getBlockAt"));
        assertTrue(change.contains("bridge$getWorldOrNull()"));
        assertTrue(change.indexOf("toWorld == null") < change.indexOf("new Location"));
        assertTrue(text.contains("WorldUUIDLeast"));
        int uuid = text.indexOf("WorldUUIDLeast");
        assertTrue(text.indexOf("bridge$getWorld()", uuid) > uuid, "world identity on save still uses the strict accessor");
    }

    @Test
    void missingWorldSkipsCollisionAndDimensionEvents() {
        boolean[] collisionFired = {false};
        boolean[] dimensionFired = {false};
        assertTrue(collide(null, collisionFired));
        assertFalse(collisionFired[0]);
        String kept = "origin";
        String result = change(null, kept, dimensionFired);
        assertFalse(dimensionFired[0]);
        assertTrue(result.equals(kept));
    }

    private static boolean collide(Object bworld, boolean[] fired) {
        if (bworld == null) {
            return true;
        }
        fired[0] = true;
        return true;
    }

    private static String change(Object toWorld, String transition, boolean[] fired) {
        if (toWorld == null) {
            return transition;
        }
        fired[0] = true;
        return "rewritten";
    }

    private static String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        int end = text.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0 && end > start, startToken);
        return text.substring(start, end);
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/entity/EntityMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/entity/EntityMixin.java");
        }
        return Files.readString(path);
    }
}
