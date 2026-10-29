/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
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

/** The opt-in world-set readiness rows: off by default, three rows only while the instance switch
 * is on, reset clears, and the off/on position changes no other stable reading. The readout itself
 * is driven from the internal A02a snapshot/event shape and never names a world handle. */
class WorldSetReadoutTest {

    private static final String A = "minecraft:overworld";
    private static final String B = "minecraft:the_nether";
    private static final String C = "minecraft:the_end";
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
        module.worldSetReadout().reset();
        module.resetReadings();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    @Test
    void theReadoutCountsWorldsAndReportsMissingPlanOrCommitIds() {
        WorldSetReadout readout = new WorldSetReadout();
        readout.observe(List.of(A, B), List.of(A), List.of(B));
        assertEquals(2L, readout.worldCount());
        assertEquals(0L, readout.idMismatch(), "all plan/commit ids are in the current set");
        assertEquals(0L, readout.setChangeEvents(), "the first observation is the baseline");

        readout.observe(List.of(A, B), List.of(A, C), List.of(B));
        assertEquals(2L, readout.worldCount());
        assertEquals(1L, readout.idMismatch(), "one plan id is outside the current set");
        assertEquals(0L, readout.setChangeEvents());
    }

    @Test
    void theReadoutCountsCreationRemovalEventsAndResetClears() {
        WorldSetReadout readout = new WorldSetReadout();
        readout.observe(List.of(A), List.of(A), List.of(A));
        readout.observe(List.of(A, B), List.of(A, B), List.of(A, B));
        assertEquals(2L, readout.worldCount());
        assertEquals(1L, readout.setChangeEvents(), "one creation is one event");
        readout.observe(List.of(A), List.of(A), List.of(A));
        assertEquals(1L, readout.worldCount());
        assertEquals(2L, readout.setChangeEvents(), "the removal adds one event");

        readout.reset();
        assertEquals(0L, readout.worldCount());
        assertEquals(0L, readout.idMismatch());
        assertEquals(0L, readout.setChangeEvents());
        readout.observe(List.of(A), List.of(A), List.of(A));
        assertEquals(1L, readout.worldCount());
        assertEquals(0L, readout.setChangeEvents(), "the first observation after reset is a baseline");
    }

    @Test
    void theDefaultOffConfigurationEmitsNoWorldRows() {
        assertFalse(KernelSettings.worldSetReadouts(), "the new switch is off by default");
        List<String> lines = KernelReadings.export(KernelModule.instance());
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.count=")));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.id_mismatch=")));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.set_change_events=")));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("world.lifecycle_denied=")));
    }

    @Test
    void theOpenSwitchEmitsTheWorldRowsAndResetClears() throws IOException {
        writeConfig(true);
        assertTrue(KernelSettings.worldSetReadouts());
        KernelModule module = KernelModule.instance();
        module.worldLifecycle().reset();
        module.worldSetReadout().observe(List.of(A, B), List.of(A), List.of(B));

        List<String> lines = KernelReadings.export(module);
        assertTrue(lines.contains("world.count=2"));
        assertTrue(lines.contains("world.id_mismatch=0"));
        assertTrue(lines.contains("world.set_change_events=0"));
        assertTrue(lines.contains("world.lifecycle_denied=0"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("world.unload_flush=")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("world.unload_token_reclaim=")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("world.unload_arena_return=")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("world.unload_counted=")));

        module.worldSetReadout().reset();
        List<String> afterReset = KernelReadings.export(module);
        assertTrue(afterReset.contains("world.count=0"));
        assertTrue(afterReset.contains("world.id_mismatch=0"));
        assertTrue(afterReset.contains("world.set_change_events=0"));
        assertTrue(afterReset.contains("world.lifecycle_denied=0"));
    }

    @Test
    void theModuleUpdatesTheReadoutOnlyWhileTheSwitchIsOn() throws IOException {
        KernelModule module = KernelModule.instance();
        module.worldSetReadout().reset();
        module.serverTick(List.of(A, B));
        assertEquals(0L, module.worldSetReadout().worldCount(),
            "with the switch off the module does not update the readout");

        writeConfig(true);
        module.worldSetReadout().reset();
        module.serverTick(List.of(A, B));
        assertEquals(2L, module.worldSetReadout().worldCount());
        module.serverTick(List.of(A));
        assertEquals(1L, module.worldSetReadout().worldCount());
        assertEquals(1L, module.worldSetReadout().setChangeEvents());
    }

    @Test
    void theSwitchChangesNoOtherStableReading() throws IOException {
        KernelModule module = KernelModule.instance();
        List<String> off = KernelReadings.export(module);

        writeConfig(true);
        List<String> on = KernelReadings.export(module);
        List<String> filtered = on.stream()
            .filter(line -> !line.startsWith("world.count="))
            .filter(line -> !line.startsWith("world.id_mismatch="))
            .filter(line -> !line.startsWith("world.set_change_events="))
            .toList();
        assertEquals(stable(off), stable(filtered),
            "the world-set readout switch changes no other stable reading");
    }

    private static List<String> stable(List<String> lines) {
        return lines.stream().filter(line -> {
            String key = line.substring(0, line.indexOf('='));
            if (key.contains("wallclock") || key.endsWith("_ms") || key.contains("observed")) {
                return false;
            }
            return key.startsWith("plan.") || key.startsWith("r02.")
                || key.startsWith("commit.") || key.startsWith("write.controlled.")
                || key.startsWith("write.path.") || key.startsWith("token.")
                || key.startsWith("tick.") || key.startsWith("arrival.")
                || key.startsWith("digest.") || key.startsWith("pipeline.");
        }).toList();
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
