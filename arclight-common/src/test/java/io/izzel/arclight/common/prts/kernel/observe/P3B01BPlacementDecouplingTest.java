/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mailbox to world placement published by the existing tick without a digest window: worlds
 * are bound for the internal world-resource meter at the bounded placement interval, the digest rows
 * stay untouched, an armed digest keeps its own placement, and unload/reset clear it. */
class P3B01BPlacementDecouplingTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final String WORLD_A = "world-a";
    private static final String WORLD_B = "world-b";

    private final KernelModule module = KernelModule.instance();
    private final Object mailboxA = new Object();
    private final Object mailboxB = new Object();

    @BeforeEach
    void switchTheKernelOn() throws IOException {
        deleteTree(CONFIG_DIR);
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), PrtsConfigManager.defaults(entry),
            StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
        System.setProperty("arclight.prts.kernel", "true");
        module.noteTickThread(Thread.currentThread());
        module.resetReadings();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
    }

    @AfterEach
    void restoreTheDeclaredDefaults() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removePipelineRowTap();
        module.resetReadings();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
        PrtsPipelineRows.install(null);
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    @Test
    void theDecoupledPublisherBindsWorldsWithoutADigestWindow() {
        TickDigestObserver digest = module.tickDigest();
        assertFalse(digest.armed());

        digest.publishPlacement(Map.of(mailboxA, WORLD_A));

        assertEquals(WORLD_A, PrtsPipelineRows.worldOf(mailboxA));
        assertTrue(digest.rows().isEmpty(), "the decoupled entry folds no digest row");
    }

    @Test
    void theWorldResourceMeterAttributesAReportFromTheDecoupledPlacement() {
        module.tickDigest().publishPlacement(Map.of(mailboxA, WORLD_A));
        WorldResChildMeter meter = new WorldResChildMeter();
        PipelineRowObserver observer = new PipelineRowObserver();
        observer.childMeter(meter);
        meter.openWindow(5L, Map.of(WORLD_A, 3L));

        PrtsPipelineRows.install(observer);
        try {
            PrtsPipelineRows.task(mailboxA, "worldgen", 1_000L);
        } finally {
            PrtsPipelineRows.install(null);
        }

        WorldResChildMeter.Sample sample = meter.drain().samples().get(WORLD_A);
        assertNotNull(sample);
        assertEquals(WORLD_A, sample.worldId());
        assertEquals(1_000L, sample.genTaskNanos());
    }

    @Test
    void theExistingTickPublishesAtTheBoundedPlacementInterval() {
        TickDigestObserver digest = module.tickDigest();
        long attempts = digest.placementAttempts();
        long refreshes = digest.placementRefreshes();

        for (int tick = 0; tick < 100; tick++) {
            module.serverTick(List.of(WORLD_A));
        }

        long calls = digest.placementAttempts() - attempts;
        long due = digest.placementRefreshes() - refreshes;
        assertEquals(100L, calls, "the existing tick calls the decoupled publisher once per tick");
        assertTrue(due >= 1L, "the first covered tick refreshes the empty placement");
        assertTrue(due <= 6L, "the placement refresh is bounded by the declared interval");
        assertFalse(digest.armed());
        assertEquals(0L, digest.placementPublishes(), "no server source is named offline");
    }

    @Test
    void aDisabledShareTableCostsNothing() throws IOException {
        switchShareTable(false);
        TickDigestObserver digest = module.tickDigest();
        long attempts = digest.placementAttempts();

        for (int tick = 0; tick < 30; tick++) {
            module.serverTick(List.of(WORLD_A));
        }

        assertEquals(attempts, digest.placementAttempts(),
            "the publisher runs only while the world-resource meter is installed");
    }

    @Test
    void aDisabledKernelCostsNothing() {
        System.setProperty("arclight.prts.kernel", "false");
        module.resetReadings();
        TickDigestObserver digest = module.tickDigest();
        long attempts = digest.placementAttempts();

        for (int tick = 0; tick < 10; tick++) {
            module.serverTick(List.of(WORLD_A));
        }

        assertEquals(attempts, digest.placementAttempts());
        assertTrue(PrtsPipelineRows.worldOf(mailboxA).equals(PrtsPipelineRows.UNPLACED_WORLD));
    }

    @Test
    void forgettingAWorldDropsOnlyItsPlacements() {
        TickDigestObserver digest = module.tickDigest();
        digest.publishPlacement(Map.of(mailboxA, WORLD_A, mailboxB, WORLD_B));

        assertEquals(1, digest.forgetMailboxPlacement(WORLD_A));

        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA));
        assertEquals(WORLD_B, PrtsPipelineRows.worldOf(mailboxB));
    }

    @Test
    void aResetClearsTheDecoupledPlacement() {
        TickDigestObserver digest = module.tickDigest();
        digest.publishPlacement(Map.of(mailboxA, WORLD_A));

        module.resetReadings();

        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA));
        assertTrue(digest.publishedPlacement().isEmpty());
    }

    @Test
    void theUnloadPathDropsThePlacementOfTheUnloadedWorld() {
        TickDigestObserver digest = module.tickDigest();
        digest.publishPlacement(Map.of(mailboxA, WORLD_A, mailboxB, WORLD_B));
        module.serverTick(List.of(WORLD_A, WORLD_B));

        module.flushWorldAtStop(WORLD_A);

        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA));
        assertEquals(WORLD_B, PrtsPipelineRows.worldOf(mailboxB));
    }

    @Test
    void anArmedDigestKeepsItsOwnPlacement() {
        TickDigestObserver armed = new TickDigestObserver(2, List.of());
        assertTrue(armed.armed());

        armed.publishPlacement(Map.of(mailboxA, WORLD_A));

        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA),
            "an armed digest owns the placement; the decoupled entry must not bind it");
        assertTrue(armed.publishedPlacement().isEmpty());
        assertEquals(0, armed.forgetMailboxPlacement(WORLD_A));
        armed.clearMailboxPlacement();
        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA));
    }

    @Test
    void aChangedPlacementReplacesTheOldMappingInsteadOfMixingIt() {
        TickDigestObserver digest = module.tickDigest();
        digest.publishPlacement(Map.of(mailboxA, WORLD_A));

        digest.publishPlacement(Map.of(mailboxA, WORLD_B));

        assertEquals(WORLD_B, PrtsPipelineRows.worldOf(mailboxA));
        assertEquals(1, digest.forgetMailboxPlacement(WORLD_B));
        assertEquals(PrtsPipelineRows.UNPLACED_WORLD, PrtsPipelineRows.worldOf(mailboxA));
    }

    private static void switchShareTable(boolean enabled) throws IOException {
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String defaults = PrtsConfigManager.defaults(entry);
        String content = enabled ? defaults
            : defaults.replace("share-table: true", "share-table: false");
        if (!enabled) {
            assertFalse(content.equals(defaults), "the default configuration declares share-table");
        }
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
}
