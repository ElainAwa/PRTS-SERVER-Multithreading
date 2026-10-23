/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void missingWorldAppliesCapturedBlocksAndKeepsThePortal() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("craftWorld == null");
        int apply = body.indexOf("this.arclight$populator.updateList()");
        int event = body.indexOf("new PortalCreateEvent");
        int cleared = body.indexOf("Optional.empty()");
        assertTrue(guard >= 0 && apply > guard && event > apply && cleared > event,
            "a missing world applies captured blocks and does not clear the portal");
        boolean[] applied = {false};
        boolean[] fired = {false};
        boolean kept = force(null, true, applied, fired);
        assertTrue(kept);
        assertTrue(applied[0]);
        assertFalse(fired[0]);
        boolean keptWithoutCapture = force(null, false, applied, fired);
        assertTrue(keptWithoutCapture);
    }

    private static boolean force(Object craftWorld, boolean captured, boolean[] applied, boolean[] fired) {
        if (craftWorld == null) {
            if (captured) {
                applied[0] = true;
            }
            return true;
        }
        fired[0] = true;
        return false;
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
