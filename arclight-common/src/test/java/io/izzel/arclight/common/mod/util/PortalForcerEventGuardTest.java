/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class PortalForcerEventGuardTest {

    @Test
    void portalForcerReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new PortalCreateEvent");
        assertTrue(accessor >= 0 && event > accessor, "the portal event is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "portal forcing does not call the strict accessor");
    }

    @Test
    void missingWorldAppliesCapturedBlocksBeforeTheEvent() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("craftWorld == null");
        int apply = body.indexOf("this.arclight$populator.updateList()");
        int event = body.indexOf("new PortalCreateEvent");
        int cleared = body.indexOf("Optional.empty()");
        assertTrue(guard >= 0 && apply > guard && event > apply && cleared > event,
            "a missing world applies captured blocks and does not clear the portal");
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/portal/PortalForcerMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/portal/PortalForcerMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void arclight$portalCreate");
        assertTrue(start >= 0);
        int next = text.indexOf("private transient BlockStateListPopulator");
        assertTrue(next > start);
        return text.substring(start, next);
    }
}
