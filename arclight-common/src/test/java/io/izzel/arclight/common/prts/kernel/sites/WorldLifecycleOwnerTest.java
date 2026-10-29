/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.WorldLifecycleWiring;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The one-owner world lifecycle state machine and its opt-in denial row. A request from a
 * non-owner thread or an illegal transition is refused and counted; no method waits for another
 * thread. The existing worldlife wait row is checked as the structural wait point. */
class WorldLifecycleOwnerTest {

    private static final String A = "minecraft:overworld";
    private static final String B = "minecraft:the_nether";
    private static final Path CONFIG_DIR = PrtsConfigManager.directory();

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
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.removePipelineRowTap();
        module.removeLoadProbe();
        module.worldLifecycle().forgetAll();
        module.worldSetReadout().reset();
        module.resetReadings();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    @Test
    void theOwnerWalksTheSevenStatesAndRefusesIllegalEdges() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertEquals(0L, owner.deniedCount());

        assertTrue(owner.requestCreate(A));
        assertEquals(WorldLifecycleOwner.State.CREATING, owner.stateOf(A));
        assertFalse(owner.requestCreate(A), "a second creation of the same world is refused");
        assertEquals(1L, owner.deniedCount());

        assertTrue(owner.transition(A, WorldLifecycleOwner.State.LOADING));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.READY));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.PINNED));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.READY));
        assertTrue(owner.requestUnload(A));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.RECLAIMING));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.GONE));
        assertEquals(WorldLifecycleOwner.State.GONE, owner.stateOf(A));
        assertFalse(owner.transition(A, WorldLifecycleOwner.State.READY),
            "a gone world has no direct edge back to ready");
        assertEquals(2L, owner.deniedCount());
        assertEquals(8L, owner.transitionCount());
    }

    @Test
    void aNonOwnerThreadCannotCreateOrUnloadAndTheOwnerBindingIsUnique() throws InterruptedException {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        AtomicBoolean created = new AtomicBoolean(true);
        AtomicBoolean unloaded = new AtomicBoolean(true);
        Thread other = new Thread(() -> {
            created.set(owner.requestCreate(A));
            unloaded.set(owner.requestUnload(A));
        });
        other.start();
        other.join();
        assertFalse(created.get(), "a non-owner creation is refused");
        assertFalse(unloaded.get(), "a non-owner unload is refused");
        assertEquals(2L, owner.deniedCount());
        assertEquals(null, owner.stateOf(A));
        assertFalse(owner.bindOwner(other), "the owner binding cannot move to another thread");
    }

    @Test
    void theDeniedCountStartsAtZeroAndResetClearsIt() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        owner.bindOwner(Thread.currentThread());
        assertEquals(0L, owner.deniedCount());
        assertFalse(owner.requestUnload(B));
        assertEquals(1L, owner.deniedCount());
        owner.reset();
        assertEquals(0L, owner.deniedCount());
        assertEquals(0, owner.worldCount());
        assertEquals(0L, owner.transitionCount());
    }

    @Test
    void theWorldlifeWaitPointExistsInTheRegistry() {
        assertTrue(WaitPointRegistry.CONTRACT_ROWS.contains("worldlife"));
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        assertTrue(registry.rows().stream().anyMatch(row -> row.wpId().equals("worldlife")));
    }

    @Test
    void resetKeepsLoadedWorldsAndLetsALegalUnloadThrough() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertTrue(owner.requestCreate(A));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.LOADING));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.READY));
        assertFalse(owner.requestCreate(A));
        assertEquals(1L, owner.deniedCount());

        owner.reset();
        assertEquals(WorldLifecycleOwner.State.READY, owner.stateOf(A),
            "reset keeps the loaded world state");
        assertEquals(1, owner.worldCount());
        assertEquals(0L, owner.deniedCount());
        assertEquals(0L, owner.transitionCount());

        assertTrue(owner.requestUnload(A), "a legal unload after reset is not refused");
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.RECLAIMING));
        assertTrue(owner.transition(A, WorldLifecycleOwner.State.GONE));
        assertEquals(0L, owner.deniedCount());
        assertEquals(3L, owner.transitionCount());
    }

    @Test
    void aReadoutResetAfterARealLoadStillAllowsTheHostUnload() throws IOException {
        writeConfig(true);
        KernelModule module = KernelModule.instance();
        module.worldLifecycle().forgetAll();
        WorldLifecycleWiring.onLevelLoaded(A);
        assertEquals(WorldLifecycleOwner.State.READY, module.worldLifecycle().stateOf(A));
        module.worldLifecycle().reset();
        WorldLifecycleWiring.onLevelUnloaded(A);
        assertEquals(WorldLifecycleOwner.State.GONE, module.worldLifecycle().stateOf(A));
        assertEquals(0L, module.worldLifecycle().deniedCount(),
            "a reset readout must not turn the host's legal unload into a denial");
    }

    @Test
    void theRealEntryWiringIsInertWhileTheSwitchIsOff() {
        KernelModule module = KernelModule.instance();
        module.worldLifecycle().forgetAll();
        WorldLifecycleWiring.onLevelLoaded(A);
        assertEquals(null, module.worldLifecycle().stateOf(A),
            "with the switch off the host entry point does not touch the owner");
        assertEquals(0L, module.worldLifecycle().deniedCount());
    }

    @Test
    void theRealEntryWiringWalksARealLoadAndRefusesADuplicate() throws IOException {
        writeConfig(true);
        KernelModule module = KernelModule.instance();
        module.worldLifecycle().forgetAll();
        WorldLifecycleWiring.onLevelLoaded(A);
        assertEquals(WorldLifecycleOwner.State.READY, module.worldLifecycle().stateOf(A));
        WorldLifecycleWiring.onLevelLoaded(A);
        assertEquals(1L, module.worldLifecycle().deniedCount(),
            "the host entry cannot create one world twice");
        WorldLifecycleWiring.onLevelUnloaded(A);
        assertEquals(WorldLifecycleOwner.State.GONE, module.worldLifecycle().stateOf(A));
        WorldLifecycleWiring.onLevelUnloaded(A);
        assertEquals(2L, module.worldLifecycle().deniedCount(),
            "a second unload of the same world is refused");
    }

    @Test
    void theDefaultOffConfigurationEmitsNoLifecycleRow() {
        assertFalse(KernelSettings.worldSetReadouts());
        List<String> lines = KernelReadings.export(KernelModule.instance());
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.lifecycle_denied=")));
    }

    @Test
    void theOpenSwitchEmitsTheDenialCountAndResetClearsIt() throws IOException {
        writeConfig(true);
        KernelModule module = KernelModule.instance();
        WorldLifecycleOwner owner = module.worldLifecycle();
        owner.forgetAll();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertTrue(owner.requestCreate(A));
        assertFalse(owner.requestCreate(A));

        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.contains("world.lifecycle_denied=1"));
        owner.reset();
        List<String> reset = KernelReadings.export(module);
        assertTrue(reset.contains("world.lifecycle_denied=0"));
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
