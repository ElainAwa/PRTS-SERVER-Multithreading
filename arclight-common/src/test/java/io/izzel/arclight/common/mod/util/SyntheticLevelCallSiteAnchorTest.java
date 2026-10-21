package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the synthetic-level guards added on the worldgen event call sites: the world is read
 * through the non-throwing accessor, and a level without a CraftWorld skips only the Bukkit
 * event while the vanilla step keeps running. */
class SyntheticLevelCallSiteAnchorTest {

    private static Path source(String pkg, String name) {
        Path p = Path.of("src/main/java/io/izzel/arclight/common/" + pkg, name);
        if (!Files.exists(p)) {
            p = Path.of("arclight-common/src/main/java/io/izzel/arclight/common/" + pkg, name);
        }
        assertTrue(Files.exists(p), p.toString());
        return p;
    }

    @Test
    void gameEventReadsTheWorldWithoutThrowing() throws Exception {
        String text = Files.readString(source("mixin/core/world/level/gameevent",
            "GameEventDispatcherMixin.java"));
        int accessor = text.indexOf("bridge$getWorldOrNull()");
        int ctor = text.indexOf("new GenericGameEvent");
        assertTrue(accessor >= 0, "game events use the non-throwing world accessor");
        assertTrue(ctor > accessor, "the guarded accessor precedes the event construction");
        assertTrue(text.contains("craftWorld == null"), "a level without a CraftWorld skips the event");
        assertFalse(text.contains("this.level.bridge$getWorld()"),
            "game events do not call the strict world accessor");
    }

    @Test
    void structureSpawnAndDecorationsTolerateMissingWorld() throws Exception {
        String text = Files.readString(source("mixin/core/world/level/chunk",
            "ChunkGeneratorMixin.java"));
        int spawnAccessor = text.indexOf("bridge$getWorldOrNull()");
        int spawnEvent = text.indexOf("new org.bukkit.event.world.AsyncStructureSpawnEvent");
        assertTrue(spawnAccessor >= 0 && spawnEvent > spawnAccessor,
            "structure spawn reads the world before building the event");
        assertTrue(text.contains("craftWorld != null"), "structure spawn skips the event without a world");
        int decorations = text.indexOf("void addDecorations(");
        assertTrue(decorations > 0, "decorations path is present");
        String decorationsBody = text.substring(decorations);
        assertTrue(decorationsBody.contains("world != null && !world.getPopulators().isEmpty()"),
            "decorations skip the populator call when no world is present");
        assertFalse(text.contains(".bridge$getWorld()"), "chunk generation no longer calls the strict accessor");
    }

    @Test
    void generatedRegionDelegatesTheNonThrowingAccessor() throws Exception {
        String text = Files.readString(source("mixin/core/server/level", "WorldGenRegionMixin.java"));
        assertTrue(text.contains("public CraftWorld bridge$getWorldOrNull()"),
            "the generated region exposes the non-throwing accessor");
        assertTrue(text.contains("((InjectLevelBridge) this.level).bridge$getWorldOrNull()"),
            "the generated region delegates to its level without throwing");
    }
}