/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class PressurePlateEventGuardTest {

    @Test
    void pressurePlateReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new EntityInteractEvent");
        assertTrue(accessor >= 0 && event > accessor, "the interact event is built only after the world is read");
        assertFalse(body.contains(".bridge$getWorld()"), "pressure plates do not call the strict accessor");
    }

    @Test
    void missingWorldDoesNotTreatTheStepAsCancelled() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bworld != null");
        int skip = body.indexOf("continue;");
        int powered = body.indexOf("return 15;");
        assertTrue(guard >= 0 && skip > guard && powered > guard,
            "cancellation stays inside the world check, and the plate can still power");
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/PressurePlateBlockMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/PressurePlateBlockMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("int getSignalStrength");
        assertTrue(start >= 0);
        return text.substring(start);
    }
}
