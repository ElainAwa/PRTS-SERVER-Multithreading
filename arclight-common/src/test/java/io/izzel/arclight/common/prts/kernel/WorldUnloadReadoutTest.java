/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.sites.WorldLifecycleOwner;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The four unload rows and the four-step return: a valid unload is flushed, its tokens are
 * released, its arena segments and per-world share state are returned, and the session is counted.
 * The rows are opt-in with world-set-readouts and reset clear them; the four counters are cumulative
 * and non-increasing down the flush -> token -> arena -> counted chain. */
class WorldUnloadReadoutTest {

    private static final String A = "p2-a04-world-a";
    private static final String B = "p2-a04-world-b";
    private static final String C = "p2-a04-world-c";
    private static final String MISSING = "p2-a04-owner-missing";
    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private final HostDriveFixture fixture = new HostDriveFixture();
    private final KernelModule module = KernelModule.instance();

    @BeforeEach
    void startFromTheDefaultOffConfiguration() throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        System.setProperty("arclight.prts.kernel", "true");
        writeConfig(false);
    }

    @AfterEach
    void restoreTheDeclaredDefaults() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.removePipelineRowTap();
        module.removeLoadProbe();
        module.worldLifecycle().reset();
        module.worldSetReadout().reset();
        module.resetReadings();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    private void loadOwner(String worldId) {
        module.worldLifecycle().bindOwner(Thread.currentThread());
        assertTrue(module.worldLifecycle().requestCreate(worldId));
        assertTrue(module.worldLifecycle().transition(worldId, WorldLifecycleOwner.State.LOADING));
        assertTrue(module.worldLifecycle().transition(worldId, WorldLifecycleOwner.State.READY));
    }

    @Test
    void theDefaultOffConfigurationEmitsNoUnloadRows() {
        assertFalse(KernelSettings.worldSetReadouts());
        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.unload_")));
    }

    @Test
    void aValidUnloadWalksTheFourStagesAndEmitsFourRows() throws IOException {
        writeConfig(true);
        String a = "p2-a04-valid-a";
        String b = "p2-a04-valid-b";
        long flush = module.unloadFlushCount();
        long token = module.unloadTokenReclaimCount();
        long arena = module.unloadArenaReturnCount();
        long counted = module.unloadCountedCount();

        fixture.tick(List.of(a, b));
        loadOwner(a);
        loadOwner(b);
        fixture.stop(List.of(a, b));

        long flushDelta = module.unloadFlushCount() - flush;
        long tokenDelta = module.unloadTokenReclaimCount() - token;
        long arenaDelta = module.unloadArenaReturnCount() - arena;
        long countedDelta = module.unloadCountedCount() - counted;
        assertEquals(2L, flushDelta);
        assertEquals(2L, tokenDelta);
        assertEquals(2L, arenaDelta);
        assertEquals(2L, countedDelta);
        assertTrue(flushDelta >= tokenDelta && tokenDelta >= arenaDelta
            && arenaDelta >= countedDelta && countedDelta >= 0L,
            "the four counters are non-increasing down the unload chain");

        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.contains("world.unload_flush=" + module.unloadFlushCount()));
        assertTrue(lines.contains("world.unload_token_reclaim=" + module.unloadTokenReclaimCount()));
        assertTrue(lines.contains("world.unload_arena_return=" + module.unloadArenaReturnCount()));
        assertTrue(lines.contains("world.unload_counted=" + module.unloadCountedCount()));
    }

    @Test
    void arenaAndShareStateOfTheUnloadedWorldAreReturned() throws IOException {
        writeConfig(true);
        String c = "p2-a04-arena";
        fixture.tick(List.of(c));
        loadOwner(c);
        ArenaLedger arena = module.arena();
        arena.claim(1L, c, "p2-a04-region", 0, 1);
        module.shares().noteOverrun(
            new ShareTable.ShareRow(c, ShareClass.WORLDRES, 0.0, 1.0), "p2-a04-site");
        assertEquals(1L, module.shares().worldOverrunCount(c));
        long arenaReturnBefore = module.unloadArenaReturnCount();

        module.flushWorldAtStop(c);

        assertEquals(0, arena.pinnedCount(), "the unloaded world's arena slots are returned");
        assertEquals(0L, module.shares().worldOverrunCount(c),
            "the unloaded world's share overrun state is returned");
        assertEquals(arenaReturnBefore + 1L, module.unloadArenaReturnCount());
    }

    @Test
    void aLiveEpochWhoseOwnerDoesNotHoldItDoesNotAdvanceTheFourRows() throws IOException {
        writeConfig(true);
        String missing = "p2-a04-missing-owner";
        fixture.tick(List.of(missing));
        long flush = module.unloadFlushCount();
        long token = module.unloadTokenReclaimCount();
        long arena = module.unloadArenaReturnCount();
        long counted = module.unloadCountedCount();
        long denied = module.worldLifecycle().deniedCount();

        WorldLifecycleWiring.onLevelUnloaded(missing);

        assertEquals(flush, module.unloadFlushCount(),
            "a live epoch the owner does not hold must not advance the flush row");
        assertEquals(token, module.unloadTokenReclaimCount());
        assertEquals(arena, module.unloadArenaReturnCount());
        assertEquals(counted, module.unloadCountedCount());
        assertEquals(denied + 1L, module.worldLifecycle().deniedCount());
        assertEquals(null, module.worldLifecycle().stateOf(missing));
    }

    @Test
    void theStoppingEventAloneDoesNotCountAWorldTheOwnerDoesNotHold() throws IOException {
        writeConfig(true);
        String missing = "p2-a04-stopping-missing";
        fixture.tick(List.of(missing));
        long flush = module.unloadFlushCount();
        long token = module.unloadTokenReclaimCount();
        long arena = module.unloadArenaReturnCount();
        long counted = module.unloadCountedCount();
        long denied = module.worldLifecycle().deniedCount();

        WorldLifecycleWiring.onServerStopping(List.of(missing));

        assertEquals(flush, module.unloadFlushCount(),
            "the stopping event records no A04 session before the owner accepts");
        assertEquals(token, module.unloadTokenReclaimCount());
        assertEquals(arena, module.unloadArenaReturnCount());
        assertEquals(counted, module.unloadCountedCount());

        WorldLifecycleWiring.onLevelUnloaded(missing);
        assertEquals(flush, module.unloadFlushCount(),
            "the owner refusal discards the pending event and advances no row");
        assertEquals(denied + 1L, module.worldLifecycle().deniedCount());
        assertEquals(null, module.worldLifecycle().stateOf(missing));
    }

    @Test
    void theStoppingEventAndHostUnloadCountOnceAndLeaveTheOwnerGone() throws IOException {
        writeConfig(true);
        String world = "p2-a04-stopping-held";
        fixture.tick(List.of(world));
        loadOwner(world);
        long flush = module.unloadFlushCount();
        long token = module.unloadTokenReclaimCount();
        long arena = module.unloadArenaReturnCount();
        long counted = module.unloadCountedCount();
        long denied = module.worldLifecycle().deniedCount();

        WorldLifecycleWiring.onServerStopping(List.of(world));
        assertEquals(flush, module.unloadFlushCount(),
            "the stopping event alone does not count the session");

        WorldLifecycleWiring.onLevelUnloaded(world);

        assertEquals(flush + 1L, module.unloadFlushCount(),
            "the owner-accepted unload counts the event session exactly once");
        assertEquals(token + 1L, module.unloadTokenReclaimCount());
        assertEquals(arena + 1L, module.unloadArenaReturnCount());
        assertEquals(counted + 1L, module.unloadCountedCount());
        assertEquals(denied, module.worldLifecycle().deniedCount(),
            "the legal stopping combination does not add a second denial");
        assertEquals(WorldLifecycleOwner.State.GONE, module.worldLifecycle().stateOf(world));
    }

    @Test
    void theRealHostUnloadBridgeRunsTheFourSteps() throws IOException {
        writeConfig(true);
        module.worldLifecycle().reset();
        String bridge = "p2-a04-bridge";
        fixture.tick(List.of(bridge));
        long flush = module.unloadFlushCount();
        long counted = module.unloadCountedCount();

        WorldLifecycleWiring.onLevelLoaded(bridge);
        WorldLifecycleWiring.onLevelUnloaded(bridge);

        assertEquals(flush + 1L, module.unloadFlushCount());
        assertEquals(counted + 1L, module.unloadCountedCount());
        assertTrue(module.unloadFlushCount() >= module.unloadTokenReclaimCount()
            && module.unloadTokenReclaimCount() >= module.unloadArenaReturnCount()
            && module.unloadArenaReturnCount() >= module.unloadCountedCount());
    }

    @Test
    void resetClearsTheFourRowsAndLeavesTheHostUnloadPathLoud() throws IOException {
        writeConfig(true);
        String reset = "p2-a04-reset";
        fixture.tick(List.of(reset));
        loadOwner(reset);
        fixture.stop(List.of(reset));
        assertTrue(module.unloadCountedCount() > 0L);

        module.resetReadings();
        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.contains("world.unload_flush=0"));
        assertTrue(lines.contains("world.unload_token_reclaim=0"));
        assertTrue(lines.contains("world.unload_arena_return=0"));
        assertTrue(lines.contains("world.unload_counted=0"));
    }

    @Test
    void theCrossWorldWaitPointExistsWithoutAddingABarrier() {
        assertTrue(WaitPointRegistry.CONTRACT_ROWS.contains("xworld"));
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        assertTrue(registry.rows().stream().anyMatch(row -> row.wpId().equals("xworld")));
    }

    private static void writeConfig(boolean worldSetReadouts) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  world-set-readouts: false",
                "  world-set-readouts: " + worldSetReadouts);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
