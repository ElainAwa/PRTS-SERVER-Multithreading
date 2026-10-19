package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPhysicsCallSiteAnchorTest {

    private static Path source(String module, String rel) {
        Path[] candidates = {
            Path.of(module, "src/main/java/" + rel),
            Path.of("..", module, "src/main/java/" + rel)
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        Path p = candidates[0];
        assertTrue(Files.exists(p), p.toString());
        return p;
    }

    private static void assertGuarded(String module, String rel) throws Exception {
        String text = Files.readString(source(module, rel));
        int orNull = text.indexOf("bridge$getWorldOrNull() != null");
        int ctor = text.indexOf("new BlockPhysicsEvent");
        assertTrue(orNull >= 0, rel + " uses the non-throwing accessor");
        assertTrue(ctor > orNull, rel + " guards before constructing the event");
        assertFalse(text.contains("bridge$getWorld() != null"), rel + " has no strict accessor guard");
    }

    @Test
    void neoforgeCallSiteUsesNonNullGuard() throws Exception {
        assertGuarded("arclight-neoforge",
            "io/izzel/arclight/neoforge/mixin/core/world/level/LevelMixin_NeoForge.java");
    }

    @Test
    void vanillaCallSiteUsesNonNullGuard() throws Exception {
        assertGuarded("arclight-common",
            "io/izzel/arclight/common/mixin/vanilla/world/level/LevelMixin_Vanilla.java");
    }
}
