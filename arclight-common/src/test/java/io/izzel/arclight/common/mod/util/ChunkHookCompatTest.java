package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkHookCompatTest {

    @Test
    void nullWorldIsSkipped() {
        assertTrue(ChunkHookCompat.shouldSkip(null));
        boolean[] ran = {false};
        assertFalse(ChunkHookCompat.runIfWorld(null, () -> ran[0] = true));
        assertFalse(ran[0]);
    }

    @Test
    void worldRunsActionOnce() {
        boolean[] ran = {false};
        assertTrue(ChunkHookCompat.runIfWorld(new Object(), () -> ran[0] = true));
        assertTrue(ran[0]);
    }

    @Test
    void loadAndUnloadEachHaveOneGuardBeforeCraftChunk() throws Exception {
        Path source = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk",
            "LevelChunkMixin.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk",
                "LevelChunkMixin.java");
        }
        String text = Files.readString(source);
        long guards = text.lines().filter(line -> line.contains("bridge$getWorldOrNull()")).count();
        assertEquals(2L, guards);
        long chunks = text.lines().filter(line -> line.contains("new CraftChunk(")).count();
        assertEquals(2L, chunks);
        int loadGuard = text.indexOf("ChunkHookCompat.shouldSkip(world)");
        int loadChunk = text.indexOf("new CraftChunk(", loadGuard);
        int unload = text.indexOf("public void unloadCallback()");
        int unloadGuard = text.indexOf("ChunkHookCompat.shouldSkip(", unload);
        int unloadChunk = text.indexOf("new CraftChunk(", unload);
        assertTrue(loadGuard >= 0 && loadChunk > loadGuard);
        assertTrue(unloadGuard >= 0 && unloadChunk > unloadGuard);
    }
}
