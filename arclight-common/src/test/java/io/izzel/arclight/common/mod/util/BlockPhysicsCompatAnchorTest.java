package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPhysicsCompatAnchorTest {

    private static Path source(String pkg, String name) {
        Path p = Path.of("src/main/java/io/izzel/arclight/common/" + pkg, name);
        if (!Files.exists(p)) {
            p = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/" + pkg, name);
        }
        assertTrue(Files.exists(p), p.toString());
        return p;
    }

    @Test
    void worldGuardRunsBeforeBlockPhysicsEventConstruction() throws Exception {
        String text = Files.readString(source("mixin/bukkit/event", "CraftEventFactoryMixin.java"));
        int accessor = text.indexOf("bridge$getWorldOrNull");
        int skip = text.indexOf("shouldSkip");
        int ctor = text.indexOf("new BlockPhysicsEvent");
        assertTrue(accessor >= 0 && ctor > accessor, "world accessor before event construction");
        assertTrue(skip > 0 && ctor > skip, "skip check before event construction");
    }

    @Test
    void blockCallersTolerateMissingEvent() throws Exception {
        String plant = Files.readString(source("mixin/core/world/level/block", "DoublePlantBlockMixin.java"));
        assertTrue(plant.contains("physics != null"), "double plant tolerates skipped event");
        String bush = Files.readString(source("mixin/core/world/level/block", "BushBlockMixin.java"));
        assertTrue(bush.contains("physics == null"), "bush block tolerates skipped event");
    }
}
