/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the event factory source and does not run the mixin.
class BlockEventWorldGuardTest {

    @Test
    void blockEventsAreBuiltButNotDispatchedWithoutAWorld() throws Exception {
        String text = source();
        String fade = between(text, "callBlockFadeEvent", "callBlockPhysicsEvent");
        String change = between(text, "callEntityChangeBlockEvent", "callRedstoneChange");
        String redstone = between(text, "callRedstoneChange", "callNotePlayEvent");
        String note = between(text, "callNotePlayEvent", "callItemSpawnEvent");

        assertTrue(fade.indexOf("getWorldOrNull()") < fade.indexOf("CraftBlockStates.getBlockState"));
        assertTrue(change.indexOf("getWorldOrNull()") < change.indexOf("CraftBlock.at"));
        assertTrue(redstone.contains("getWorldOrNull() != null"));
        assertTrue(note.contains("getWorldOrNull() != null"));
    }

    private static String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        int end = text.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0 && end > start, startToken);
        return text.substring(start, end);
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/bukkit/event/CraftEventFactoryMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/bukkit/event/CraftEventFactoryMixin.java");
        }
        return Files.readString(path);
    }
}
