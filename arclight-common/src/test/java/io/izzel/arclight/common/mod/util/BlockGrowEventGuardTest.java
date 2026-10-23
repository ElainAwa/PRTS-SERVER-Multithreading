/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void missingWorldPlacesTheBlockAndSkipsTheEvent() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bukkitWorld == null");
        int place = body.indexOf("world.setBlock(pos, newData, flag);", guard);
        int done = body.indexOf("return true;", place);
        int event = body.indexOf("new BlockGrowEvent");
        assertTrue(guard >= 0 && place > guard && done > place && event > done,
            "a missing world places the block and returns the existing success value");
        boolean[] placed = {false};
        boolean[] fired = {false};
        boolean result = grow(null, placed, fired);
        assertTrue(result);
        assertTrue(placed[0]);
        assertFalse(fired[0]);
    }

    @Test
    void presentWorldFiresTheEventAndDoesNotShortCircuit() {
        boolean[] placed = {false};
        boolean[] fired = {false};
        boolean result = grow(new Object(), placed, fired);
        assertFalse(result);
        assertFalse(placed[0]);
        assertTrue(fired[0]);
    }

    private static boolean grow(Object bukkitWorld, boolean[] placed, boolean[] fired) {
        if (bukkitWorld == null) {
            placed[0] = true;
            return true;
        }
        fired[0] = true;
        return false;
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
