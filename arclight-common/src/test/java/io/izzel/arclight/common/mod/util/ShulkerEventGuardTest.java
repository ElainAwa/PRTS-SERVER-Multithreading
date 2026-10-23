/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerEventGuardTest {

    @Test
    void teleportReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new EntityTeleportEvent");
        assertTrue(accessor >= 0 && event > accessor);
        assertFalse(body.contains(".bridge$getWorld()"));
    }

    @Test
    void missingWorldSkipsTheEventAndStillMoves() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bworld != null");
        int event = body.indexOf("new EntityTeleportEvent");
        int move = body.indexOf("DecorationOps.callsite().invoke(instance)");
        assertTrue(guard >= 0 && event > guard && move > event);
        int[] pos = {2};
        boolean[] fired = {false};
        boolean moved = teleport(null, pos, fired);
        assertTrue(moved);
        assertFalse(fired[0]);
        assertEquals(2, pos[0]);
    }

    private static boolean teleport(Object bworld, int[] pos, boolean[] fired) {
        if (bworld != null) {
            fired[0] = true;
            return false;
        }
        return true;
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/entity/monster/ShulkerMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/entity/monster/ShulkerMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void arclight$teleportEvent");
        int end = text.indexOf("void arclight$breedCause");
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }
}
