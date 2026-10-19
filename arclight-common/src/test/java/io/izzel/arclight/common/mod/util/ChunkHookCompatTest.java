package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkHookCompatTest {

    private final List<String> log = new ArrayList<>();

    @AfterEach
    void reset() {
        ChunkHookCompat.resetForTests();
    }

    @Test
    void nullLoadHookSkipsActionAndLogsOnce() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        boolean[] ran = {false};
        assertFalse(ChunkHookCompat.runChunkEventHook(null, "load", () -> ran[0] = true));
        assertFalse(ChunkHookCompat.runChunkEventHook(null, "load", () -> ran[0] = true));
        assertFalse(ran[0]);
        assertEquals(List.of("load"), log);
    }

    @Test
    void nullUnloadHookSkipsActionAndLogsOnce() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        boolean[] ran = {false};
        assertFalse(ChunkHookCompat.runChunkEventHook(null, "unload", () -> ran[0] = true));
        assertFalse(ChunkHookCompat.runChunkEventHook(null, "unload", () -> ran[0] = true));
        assertFalse(ran[0]);
        assertEquals(List.of("unload"), log);
    }

    @Test
    void entityNullHookSkipsActionAndLogsOnce() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        boolean[] ran = {false};
        assertFalse(ChunkHookCompat.runEntityEventHook(null, "entity-load", () -> ran[0] = true));
        assertFalse(ChunkHookCompat.runEntityEventHook(null, "entity-load", () -> ran[0] = true));
        assertFalse(ran[0]);
        assertEquals(List.of("entity-load"), log);
    }

    @Test
    void entityNormalHookRunsOnceWithoutLog() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        int[] runs = {0};
        assertTrue(ChunkHookCompat.runEntityEventHook(new Object(), "entity-load", () -> runs[0]++));
        assertEquals(1, runs[0]);
        assertTrue(log.isEmpty());
    }

    @Test
    void normalWorldRunsActionOnceWithoutLog() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        int[] runs = {0};
        assertTrue(ChunkHookCompat.runChunkEventHook(new Object(), "load", () -> runs[0]++));
        assertEquals(1, runs[0]);
        assertTrue(log.isEmpty());
    }

    @Test
    void bothPathsLogOnceAcrossRepeatedCalls() {
        ChunkHookCompat.setLogSinkForTests(log::add);
        ChunkHookCompat.runChunkEventHook(null, "load", () -> { });
        ChunkHookCompat.runChunkEventHook(null, "unload", () -> { });
        ChunkHookCompat.runChunkEventHook(null, "load", () -> { });
        assertEquals(List.of("load", "unload"), log);
    }

    @Test
    void entityEventGuardsBeforeCallSites() throws Exception {
        Path source = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/entity",
            "PersistentEntitySectionManagerMixin.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/entity",
                "PersistentEntitySectionManagerMixin.java");
        }
        String text = Files.readString(source);
        long guards = text.lines().filter(line -> line.contains("runEntityEventHook(")).count();
        assertEquals(2L, guards);
        int loadGuard = text.indexOf("runEntityEventHook(");
        int loadCall = text.indexOf("callEntitiesLoadEvent", loadGuard);
        int unloadCall = text.indexOf("callEntitiesUnloadEvent");
        assertTrue(loadGuard >= 0 && loadCall > loadGuard && unloadCall > 0);
    }

    @Test
    void callbacksGuardBeforeCraftChunkAndKeepOneConstructionEach() throws Exception {
        Path source = Path.of("src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk",
            "LevelChunkMixin.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/chunk",
                "LevelChunkMixin.java");
        }
        String text = Files.readString(source);
        long hooks = text.lines().filter(line -> line.contains("runChunkEventHook(")).count();
        assertEquals(2L, hooks);
        long chunks = text.lines().filter(line -> line.contains("new CraftChunk(")).count();
        assertEquals(3L, chunks);
        int loadGuard = text.indexOf("runChunkEventHook(world, \"load\"");
        int loadChunk = text.indexOf("new CraftChunk(", text.indexOf("private void arclight$loadChunkEvents"));
        int unloadGuard = text.indexOf("runChunkEventHook(world, \"unload\"");
        int unloadChunk = text.indexOf("new CraftChunk(", text.indexOf("private void arclight$unloadChunkEvents"));
        assertTrue(loadGuard >= 0 && loadChunk > loadGuard);
        assertTrue(unloadGuard >= 0 && unloadChunk > unloadGuard);
    }
}
