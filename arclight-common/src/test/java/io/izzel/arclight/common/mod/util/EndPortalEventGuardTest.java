/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class EndPortalEventGuardTest {

    @Test
    void enterAndExitReadTheWorldWithoutThrowing() throws Exception {
        String text = source();
        String enter = between(text, "void arclight$enterPortal", "void arclight$pushCause");
        String exit = text.substring(text.indexOf("void arclight$fireEvent"));
        assertTrue(enter.contains("bridge$getWorldOrNull()") && enter.indexOf("new EntityPortalEnterEvent") > enter.indexOf("bworld == null"));
        assertTrue(exit.contains("bridge$getWorldOrNull()") && exit.indexOf("toWorld == null") < exit.indexOf("bridge$callPortalEvent"));
        assertFalse(enter.contains(".bridge$getWorld()"));
        assertFalse(exit.contains(".bridge$getWorld()"));
    }

    @Test
    void missingWorldReturnsBeforeTheTeleportEvent() throws Exception {
        String exit = source().substring(source().indexOf("void arclight$fireEvent"));
        int guard = exit.indexOf("toWorld == null");
        int leave = exit.indexOf("return;");
        int event = exit.indexOf("bridge$callPortalEvent");
        int clear = exit.indexOf("setReturnValue(null)");
        assertTrue(guard >= 0 && leave > guard && event > leave && clear > event,
            "a missing destination world returns the vanilla transition");
    }

    private static String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        int end = text.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/EndPortalBlockMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/EndPortalBlockMixin.java");
        }
        return Files.readString(path);
    }
}
