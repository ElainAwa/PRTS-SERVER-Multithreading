/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.wiring.EntityDomain;
import io.izzel.arclight.common.prts.kernel.wiring.KernelWiring;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What switching the parallel plane off does to a pass that was dispatched but never merged: the
 * pass is closed with a code, its window is cleared and its epoch advances, so switching back on
 * starts from a clean ledger and the next closure holds.
 */
class KernelDispatchShutdownTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final String WORLD = "dispatch-switch";

    @BeforeEach
    void switchThePlaneOn() throws IOException {
        assumeTrue(!Files.exists(CONFIG_DIR),
            "a prts-config directory already exists in the test working directory");
        Files.createDirectories(CONFIG_DIR);
        writeConfig(true);
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
    void dispatchShutdownClosesPendingLedger() throws IOException {
        System.setProperty("arclight.prts.kernel", "true");
        assertTrue(KernelSettings.dispatchParallel(), "the test drives the switched-on position");
        KernelModule module = KernelModule.instance();
        module.resetReadings();
        // The test drives the plane the platform entry installs, not a stand-in of its own.
        EntityDomain domain = KernelWiring.install();
        DispatchReadings readings = domain.readings();
        TaskLedger ledger = domain.ledger();
        ArenaLedger arena = module.arena();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-switch-",
            Thread.NORM_PRIORITY, 8, 4), 1, readings, arena);
        try {
            WorkPlan first = WorkPlan.freeze(module.tickIndex() + 1, ledger.epoch(),
                List.of(view(8)), 4, 1L);
            domain.stage(DispatchPass.dispatch(first, pool,
                EntityIntegrator.INSTANCE, arena, readings, ledger));
            assertTrue(ledger.pendingCount() > 0L, "the staged pass registered no pending batch");
            long epochBefore = ledger.epoch();

            writeConfig(false);
            module.serverTick(List.of(WORLD));

            assertEquals(0L, ledger.pendingCount(), "closing the plane left batches pending");
            assertTrue(ledger.epoch() > epochBefore, "the epoch of a closed pass did not advance");
            assertEquals(first.taskCount(), readings.shutdownDropped());
            assertEquals(0L, readings.shutdownUnterminated());
            assertEquals(0, readings.shutdownRemaining());

            writeConfig(true);
            WorkPlan second = WorkPlan.freeze(module.tickIndex() + 1, ledger.epoch(),
                List.of(view(8)), 4, 100L);
            domain.stage(DispatchPass.dispatch(second, pool,
                EntityIntegrator.INSTANCE, arena, readings, ledger));
            module.serverTick(List.of(WORLD));

            MergeSegment.Frame frame = domain.lastFrame();
            assertTrue(frame.closureOk(), "the closure of the reopened window is broken");
            assertEquals(second.taskCount(), frame.committed());
            assertEquals(0L, ledger.pendingCount());
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    private static void writeConfig(boolean parallel) throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  self-timers: true", "  self-timers: false")
            .replace("  share-table: true", "  share-table: false")
            .replace("  wait-registry: true", "  wait-registry: false")
            .replace("  write-path-guard: true", "  write-path-guard: false")
            .replace("  dispatch-parallel: " + (parallel ? "false" : "true"),
                "  dispatch-parallel: " + parallel);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), content, StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
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

    private static EntityCandidateView view(int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, i * 4, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }
}
