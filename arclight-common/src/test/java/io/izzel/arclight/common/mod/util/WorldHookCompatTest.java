package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHookCompatTest {

    @AfterEach
    void reset() {
        WorldHookCompat.resetForTests();
    }

    @Test
    void normalCraftWorldRunsActionOnce() {
        List<String> seen = new ArrayList<>();
        WorldHookCompat.enterBukkitHook(true, () -> seen.add("A"));
        assertEquals(List.of("A"), seen);
    }

    @Test
    void missingCraftWorldSkipsAction() {
        int[] runs = {0};
        WorldHookCompat.enterBukkitHook(false, () -> runs[0]++);
        WorldHookCompat.enterBukkitHook(false, () -> runs[0]++);
        assertEquals(0, runs[0]);
    }

    @Test
    void mixedDimensionsRunOnlyCraftWorldsInOrder() {
        List<String> seen = new ArrayList<>();
        WorldHookCompat.enterBukkitHook(true, () -> seen.add("A"));
        WorldHookCompat.enterBukkitHook(false, () -> seen.add("B"));
        WorldHookCompat.enterBukkitHook(true, () -> seen.add("C"));
        assertEquals(List.of("A", "C"), seen);
    }

    @Test
    void hookExceptionPropagatesAndStateIsClean() {
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException got = assertThrows(RuntimeException.class,
            () -> WorldHookCompat.enterBukkitHook(true, () -> {
                throw boom;
            }));
        assertSame(boom, got);
        WorldHookCompat.enterBukkitHook(false, () -> { });
        WorldHookCompat.enterBukkitHook(false, () -> { });
        WorldHookCompat.resetForTests();
    }

    @Test
    void bridgeContractDefaultOrNullAndStrictGetWorld() throws Exception {
        Path source = Path.of("src/main/java/io/izzel/arclight/common/bridge/inject",
            "InjectLevelBridge.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/bridge/inject",
                "InjectLevelBridge.java");
        }
        String text = Files.readString(source);
        String orNullText = "default CraftWorld bridge$getWorldOrNull()";
        String strictText = "throw new IllegalStateException";
        assertTrue(text.contains(orNullText));
        assertTrue(text.contains(strictText));
    }

    @Test
    void sourceAnchorsProtectNineStartupHooks() throws Exception {
        Path source = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/server",
            "MinecraftServerMixin.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/server",
                "MinecraftServerMixin.java");
        }
        assertTrue(Files.exists(source), source.toString());
        String text = Files.readString(source);
        long guards = text.lines()
            .filter(line -> line.contains("no-CraftWorld guard")).count();
        assertEquals(9L, guards);
        assertTrue(text.contains("bridge$getWorldOrNull()"));
    }
}
