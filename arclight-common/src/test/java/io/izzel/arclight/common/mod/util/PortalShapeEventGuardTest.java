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

class PortalShapeEventGuardTest {

    @Test
    void portalShapeReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new PortalCreateEvent");
        assertTrue(accessor >= 0 && event > accessor, "the portal event is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "portal shape does not call the strict accessor");
    }

    @Test
    void missingWorldKeepsCapturedBlocksAndDoesNotCancel() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("world == null");
        int created = body.indexOf("this.arclight$ret = true;");
        int event = body.indexOf("new PortalCreateEvent");
        int cancel = body.indexOf("ci.cancel()");
        assertTrue(guard >= 0 && created > guard && event > created && cancel > event,
            "a missing world reports the portal created and does not cancel vanilla placement");
        List<String> captured = new ArrayList<>(List.of("frame"));
        Result result = place(null, captured);
        assertTrue(result.created);
        assertFalse(result.cancelled);
        assertFalse(result.fired);
        assertEquals(List.of("frame"), captured);
    }

    private static Result place(Object world, List<String> captured) {
        if (world == null) {
            return new Result(true, false, false);
        }
        captured.clear();
        return new Result(false, true, true);
    }

    private record Result(boolean created, boolean cancelled, boolean fired) {
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/portal/PortalShapeMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/portal/PortalShapeMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void arclight$buildPortal");
        assertTrue(start >= 0);
        int next = text.indexOf("private transient boolean arclight$ret");
        assertTrue(next > start);
        return text.substring(start, next);
    }
}
