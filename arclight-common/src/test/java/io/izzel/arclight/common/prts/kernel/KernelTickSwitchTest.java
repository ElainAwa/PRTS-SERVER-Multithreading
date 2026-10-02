/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What one driven tick does while the metering switches are off.
 *
 * <p>Reclaiming an expired owner token is part of the write-right lifecycle and not of the
 * observation of it, so it runs on a tick whatever the self timers say. The tick is driven here with
 * the metering switches off: the sweep still runs, and the timer records no row of its own and keeps
 * no tick total behind, so turning the switches back on cannot inherit the time they were off as the
 * work of one tick.</p>
 */
class KernelTickSwitchTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();

    @BeforeEach
    void switchTheMeteringOff() throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  self-timers: true", "  self-timers: false")
            .replace("  share-table: true", "  share-table: false")
            .replace("  wait-registry: true", "  wait-registry: false")
            .replace("  write-path-guard: true", "  write-path-guard: false");
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
    }

    @AfterEach
    void restoreTheDeclaredDefaults() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        KernelModule module = KernelModule.instance();
        module.removeWaitSiteTap();
        module.removeWritePathTap();
        module.resetReadings();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    @Test
    void theOwnerSweepRunsOnADrivenTickWhileTheSelfTimersAreOff() {
        System.setProperty("arclight.prts.kernel", "true");
        assertFalse(KernelSettings.selfTimers(), "the test drives the switched-off position");
        assertFalse(KernelSettings.shareTable());
        KernelModule module = KernelModule.instance();
        long tick = module.tickIndex();
        OwnerToken token = new OwnerToken("world", WriteLevel.REGION, "region-expired", 1L, 7L,
            tick, tick + 1L, HolderKind.REGISTERED, "site:a");
        assertTrue(module.owners().acquire(token));
        long sweeps = module.owners().reclaimPasses();
        double observeBefore = module.window().observeMs();

        module.serverTick(List.of("world"));

        assertTrue(module.owners().reclaimPasses() > sweeps,
            "the expiry sweep belongs to the tick and not to the metering switch");
        assertEquals(1, module.owners().expiredReclaimedCount());
        assertEquals(0, module.owners().activeTokens());
        assertEquals(observeBefore, module.window().observeMs(), 1e-9,
            "with the self timers off the tick records no observation row of its own");
        assertEquals(0L, tickTotals(),
            "with the share table off the tick totals of the tick are dropped, not carried");
    }

    private static long tickTotals() {
        long total = 0L;
        Map<String, long[]> totals = SelfTimers.consumeTickTotals();
        for (long[] row : totals.values()) {
            for (long value : row) {
                total += value;
            }
        }
        return total;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
