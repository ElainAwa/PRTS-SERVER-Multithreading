/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Source-text guard: reads the mixin source and does not run the mixin.
class BoatEventGuardTest {

    @Test
    void vehicleUpdateReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new VehicleUpdateEvent");
        assertTrue(accessor >= 0 && event > accessor);
        assertFalse(body.contains(".bridge$getWorld()"));
    }

    @Test
    void missingWorldReturnsBeforeTheLocation() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bworld == null");
        int leave = body.indexOf("return;");
        int location = body.indexOf("new Location");
        assertTrue(guard >= 0 && leave > guard && location > leave,
            "a missing world returns before a location is built");
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/entity/vehicle/BoatMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/entity/vehicle/BoatMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void arclight$updateVehicle");
        int end = text.indexOf("boolean arclight$breakVehicle");
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }
}
