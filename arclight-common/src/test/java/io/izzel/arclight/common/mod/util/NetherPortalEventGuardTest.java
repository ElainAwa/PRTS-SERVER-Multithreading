/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class NetherPortalEventGuardTest {

    @Test
    void enterAndExitReadTheWorldWithoutThrowing() throws Exception {
        String text = source();
        assertFalse(text.contains(".bridge$getWorld()"), "nether portals do not call the strict accessor");
        String enter = between(text, "void arclight$portalEnter", "DimensionTransition arclight$createPortalEvent");
        String exit = text.substring(text.indexOf("DimensionTransition arclight$createPortalEvent"));
        assertTrue(enter.contains("bridge$getWorldOrNull()") && enter.indexOf("new EntityPortalEnterEvent") > enter.indexOf("bworld == null"));
        assertTrue(exit.contains("bridge$getWorldOrNull()") && exit.indexOf("DecorationOps.callsite()") < exit.indexOf("bridge$callPortalEvent"));
    }

    @Test
    void missingDestinationCallsVanillaBeforeThePortalEvent() throws Exception {
        String exit = source().substring(source().indexOf("DimensionTransition arclight$createPortalEvent"));
        int guard = exit.indexOf("toWorld == null");
        int vanilla = exit.indexOf("DecorationOps.callsite()");
        int event = exit.indexOf("bridge$callPortalEvent");
        assertTrue(guard >= 0 && vanilla > guard && event > vanilla,
            "a missing destination world calls the vanilla exit before any portal event");
    }

    private static String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        int end = text.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }

    private static String source() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/NetherPortalBlockMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/NetherPortalBlockMixin.java");
        }
        return Files.readString(path);
    }
}
