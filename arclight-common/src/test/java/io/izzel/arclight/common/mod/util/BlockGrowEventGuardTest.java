/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class BlockGrowEventGuardTest {

    @Test
    void blockGrowReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new BlockGrowEvent");
        assertTrue(accessor >= 0, "block growth reads the world without throwing");
        assertTrue(event > accessor, "the event is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "block growth does not call the strict accessor");
    }

    @Test
    void missingWorldPlacesTheBlockBeforeTheEvent() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bukkitWorld == null");
        int place = body.indexOf("world.setBlock(pos, newData, flag);", guard);
        int done = body.indexOf("return true;", place);
        int event = body.indexOf("new BlockGrowEvent");
        assertTrue(guard >= 0 && place > guard && done > place && event > done,
            "a missing world places the block and returns the existing success value");
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/bukkit/event/CraftEventFactoryMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/bukkit/event/CraftEventFactoryMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("boolean handleBlockGrowEvent");
        assertTrue(start >= 0);
        int next = text.indexOf("public static boolean", start + 1);
        if (next < 0) {
            next = text.length();
        }
        return text.substring(start, next);
    }
}
