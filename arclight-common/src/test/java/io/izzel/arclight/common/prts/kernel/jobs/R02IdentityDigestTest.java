/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import net.minecraft.world.level.ChunkPos;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The two read-only identity keys: off by default, a freeze-level FNV-1a64 of the frozen batches
 * when opened, cleared by the kernel reset, and no other reading or producer output moved by the
 * switch. */
class R02IdentityDigestTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final String LIGHT = "light";
    private static final Object PROCESSOR = new Object();
    private static final long CELL_A = ChunkPos.asLong(0, 0);
    private static final long CELL_B = ChunkPos.asLong(1, 1);
    private static final long CELL_C = ChunkPos.asLong(2, 2);
    private static final long TICK = 5L;
    private static final long PLAN = 7L;
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
        KernelModule.instance().resetReadings();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    @Test
    void theDefaultConfigurationEmitsNoIdentityKeys() {
        assertFalse(KernelSettings.r02IdentityDigest(), "the new switch is off by default");
        List<String> lines = KernelReadings.export(KernelModule.instance());
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("r02.identity_digest=")),
            "the off switch leaves no digest row");
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("r02.identity_entries=")),
            "the off switch leaves no entry row");
    }

    @Test
    void theSameBatchesHaveTheSameDigestAndChangedCellsChangeIt() throws IOException {
        writeConfig(true);
        assertTrue(KernelSettings.r02IdentityDigest());

        String first = digestOf(MAILBOX, CELL_A, CELL_B);
        String second = digestOf(MAILBOX, CELL_A, CELL_B);
        String changed = digestOf(MAILBOX, CELL_A, CELL_C);
        assertEquals(first, second, "the same world, mailbox, task count and cells are stable");
        assertNotEquals(first, changed, "the same count over different cells is a different identity");
        assertEquals(0xb08758390920c48bL,
            PipelineRoundJobs.identityDigest(WORLD, MAILBOX, 2L, new long[]{CELL_A, CELL_B}),
            "the canonical batch fold is pinned");
        assertNotEquals("0000000000000000", first);
    }

    @Test
    void theAggregateCountsTheFrozenBatchesAndTheKernelResetClearsIt() throws IOException {
        writeConfig(true);
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = new JobIntake(() -> 64);
        mint(ledger, WORLD, MAILBOX, CELL_A);
        mint(ledger, WORLD, MAILBOX, CELL_B);
        mint(ledger, WORLD, LIGHT, CELL_C);

        assertEquals(2, jobs.declareInto(intake, PLAN, TICK));
        assertEquals(2L, jobs.identityEntries(), "one entry per frozen batch");
        assertNotEquals("0000000000000000", jobs.identityDigest());

        jobs.reset();
        assertEquals("0000000000000000", jobs.identityDigest(), "reset clears the aggregate");
        assertEquals(0L, jobs.identityEntries(), "reset clears the entry count");
    }

    @Test
    void theSwitchChangesNoOtherReading() throws IOException {
        KernelModule module = KernelModule.instance();
        List<String> off = KernelReadings.export(module);

        writeConfig(true);
        assertTrue(KernelSettings.r02IdentityDigest());
        List<String> on = KernelReadings.export(module);
        List<String> onWithoutIdentityRows = on.stream()
            .filter(line -> !line.startsWith("r02.identity_digest="))
            .filter(line -> !line.startsWith("r02.identity_entries="))
            .toList();
        assertEquals(stableReadings(off), stableReadings(onWithoutIdentityRows),
            "the plan, commit, claim and pipeline readings are bit-identical with the switch off or on");
        assertTrue(on.stream().anyMatch(line -> line.startsWith("r02.identity_digest=")),
            "the open switch emits the digest row");
        assertTrue(on.stream().anyMatch(line -> line.startsWith("r02.identity_entries=")),
            "the open switch emits the entry row");
    }

    @Test
    void theSwitchChangesNoDeclarationOrProducerCounter() throws IOException {
        Snapshot off = run();
        assertFalse(KernelSettings.r02IdentityDigest());

        writeConfig(true);
        assertTrue(KernelSettings.r02IdentityDigest());
        Snapshot on = run();

        assertEquals(off.declarations(), on.declarations(),
            "the switch adds no declaration and changes none");
        assertEquals(off.candidates(), on.candidates(),
            "the candidate table and its keys are unchanged");
        assertEquals(off.windows(), on.windows());
        assertEquals(off.declared(), on.declared());
        assertEquals(off.refused(), on.refused());
        assertEquals(off.covered(), on.covered());
        assertEquals(off.lastDeclared(), on.lastDeclared());
        assertEquals(off.lastRounds(), on.lastRounds());
        assertEquals(off.roundsSeen(), on.roundsSeen());
        assertEquals(off.tasksSeen(), on.tasksSeen());
        assertEquals("0000000000000000", off.digest());
        assertNotEquals("0000000000000000", on.digest());
    }

    /** The rows whose values are derived from the plan, commit, claim and pipeline state; the
     * observation and wall-time rows move between two exports and are not part of the switch. */
    private static List<String> stableReadings(List<String> lines) {
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

    private static Snapshot run() {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        JobIntake intake = new JobIntake(() -> 64);
        mint(ledger, WORLD, MAILBOX, CELL_A);
        mint(ledger, WORLD, MAILBOX, CELL_B);
        mint(ledger, WORLD, LIGHT, CELL_C);
        jobs.ownerRound(WORLD, MAILBOX);
        jobs.declareInto(intake, PLAN, TICK);
        return new Snapshot(intake.take(), jobs.candidateDeclarations(), jobs.windows(),
            jobs.declared(), jobs.refused(), jobs.coveredRounds(), jobs.lastDeclared(),
            jobs.lastRounds(), jobs.roundsSeen(), jobs.tasksSeen(), jobs.identityDigest());
    }

    private static String digestOf(String mailbox, long... cells) {
        PipelineBatchLedger ledger = new PipelineBatchLedger();
        PipelineRoundJobs jobs = producer(ledger);
        for (long cell : cells) {
            mint(ledger, WORLD, mailbox, cell);
        }
        jobs.declareInto(new JobIntake(() -> 64), PLAN, TICK);
        return jobs.identityDigest();
    }

    private static PipelineRoundJobs producer(PipelineBatchLedger ledger) {
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        jobs.bind(ledger);
        return jobs;
    }

    private static void mint(PipelineBatchLedger ledger, String world, String mailbox, long cell) {
        ledger.mint(PROCESSOR, world, mailbox, cell, TICK);
    }

    private static void writeConfig(boolean identityDigest) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        String content = PrtsConfigManager.defaults(entry)
            .replace("  r02-identity-digest: false",
                "  r02-identity-digest: " + identityDigest);
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

    private record Snapshot(List<JobDeclaration> declarations,
                            List<FrozenDeclarations.Candidate> candidates,
                            long windows, long declared, long refused, long covered,
                            long lastDeclared, long lastRounds, long roundsSeen, long tasksSeen,
                            String digest) {
    }
}
