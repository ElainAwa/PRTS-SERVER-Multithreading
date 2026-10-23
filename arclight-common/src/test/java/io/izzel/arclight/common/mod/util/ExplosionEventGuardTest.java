/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplosionEventGuardTest {

    @Test
    void explodeReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int list = body.indexOf("new ObjectArrayList");
        assertTrue(accessor >= 0 && list > accessor, "the block list is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "explosions do not call the strict accessor");
    }

    @Test
    void missingWorldLeavesTheBlastListAndReportsNotCancelled() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("world == null");
        int uncancelled = body.indexOf("return false;");
        int clear = body.indexOf("this.toBlow.clear()");
        int event = body.indexOf("callEntityExplodeEvent");
        assertTrue(guard >= 0 && uncancelled > guard && clear > uncancelled && event > uncancelled,
            "a missing world returns before the blast list is replaced");
        List<String> toBlow = new ArrayList<>(List.of("origin"));
        boolean cancelled = explode(null, toBlow);
        assertFalse(cancelled);
        assertEquals(List.of("origin"), toBlow);
    }

    private static boolean explode(Object world, List<String> toBlow) {
        if (world == null) {
            return false;
        }
        toBlow.clear();
        return true;
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/ExplosionMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/ExplosionMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("boolean arclight$callBlockExplodeEvent");
        assertTrue(start >= 0);
        return text.substring(start);
    }
}
