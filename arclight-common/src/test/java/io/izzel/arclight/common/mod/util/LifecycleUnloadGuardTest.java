/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: the world removal path runs while levels leave the server, and a dynamic
// level has no CraftWorld. It must read the nullable accessor instead of the strict one.
class LifecycleUnloadGuardTest {

    @Test
    void removeWorldReadsTheNullableAccessor() throws Exception {
        String text = source();
        int start = text.indexOf("void bridge$removeWorld");
        assertTrue(start >= 0, "bridge$removeWorld must exist");
        int open = text.indexOf('{', start);
        int end = matchingBrace(text, open);
        String body = text.substring(open, end);
        assertFalse(body.contains(".bridge$getWorld("), "removal must not read the strict accessor");
        assertTrue(body.contains("bridge$getWorldOrNull()") && body.contains("craftWorld == null"),
            "removal must skip a level without a CraftWorld");
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/bukkit/CraftServerMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/bukkit/CraftServerMixin.java");
        }
        return Files.readString(path);
    }
}
