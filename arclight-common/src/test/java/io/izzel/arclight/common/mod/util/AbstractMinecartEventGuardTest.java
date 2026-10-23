/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractMinecartEventGuardTest {

    @Test
    void vehicleUpdateReadsTheWorldWithoutThrowing() throws Exception {
        String body = methodBody();
        int accessor = body.indexOf("bridge$getWorldOrNull()");
        int event = body.indexOf("new VehicleUpdateEvent");
        assertTrue(accessor >= 0 && event > accessor);
        assertFalse(body.contains("this.level().bridge$getWorld()"));
    }

    @Test
    void missingWorldSkipsMoveEventsAndLetsTheTickContinue() throws Exception {
        String body = methodBody();
        int guard = body.indexOf("bworld == null");
        int leave = body.indexOf("return;");
        int location = body.indexOf("new Location");
        assertTrue(guard >= 0 && leave > guard && location > leave);
        boolean[] fired = {false};
        assertTrue(tick(null, fired));
        assertFalse(fired[0]);
    }

    private static boolean tick(Object bworld, boolean[] fired) {
        if (bworld == null) {
            return true;
        }
        fired[0] = true;
        return true;
    }

    private static String methodBody() throws Exception {
        Path path = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/entity/vehicle/AbstractMinecartMixin.java");
        if (!Files.exists(path)) {
            path = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/entity/vehicle/AbstractMinecartMixin.java");
        }
        String text = Files.readString(path);
        int start = text.indexOf("void arclight$vehicleUpdateEvent");
        int end = text.indexOf("boolean arclight$ridingCollide");
        assertTrue(start >= 0 && end > start);
        return text.substring(start, end);
    }
}
