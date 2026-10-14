/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The write path of the close seal against the real surroundings: where the file lands, what is
 * refused before it lands, and which terminal a failure gets. Nothing here starts an instance or
 * touches a world; the module is read, never driven. */
class CloseSealWriterTest {

    private static final String RUN = "20260101-000000-7";
    private static final List<String> WORLDS = List.of("minecraft:overworld", "minecraft:the_nether");
    private static final String PRE_FLUSH = "-";
    private static final String POST_FLUSH =
        "minecraft:overworld:42:3:7:1>2>3>4,minecraft:the_nether:9:0:5:5>6>7>8";

    @TempDir
    Path tmp;

    @AfterEach
    void leaveTheModuleAsItWas() {
        System.clearProperty("arclight.prts.kernel");
        System.clearProperty(CloseSealWriter.DIAG_DIR_PROPERTY);
    }

    @Test
    void theSameFileSystemGetsOneAtomicReplaceAndLeavesNoStageBehind() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        Path file = diag.resolve(RUN).resolve("close-seal.tsv");
        assertTrue(Files.isRegularFile(file), "the record file has to be in the run directory");
        assertEquals(List.of(RUN), list(diag), "no staged file may survive the rename");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        String entry = CloseSealChecks.dataLines(text).get(0);
        assertEquals("promised", CloseSealChecks.field(entry, "primaryAtomic"));
        assertEquals("ATOMIC_MOVE", CloseSealChecks.field(entry, "atomic"));
        assertEquals("-", CloseSealChecks.field(entry, "fallback"));
        assertTrue(writer.path().endsWith("close-seal.tsv"));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path target = Files.createDirectories(tmp.resolve("negative")).resolve("close-seal.tsv");
        String problem = CloseSealFiles.create(foreign, target, "x\n");
        assertNotEquals("", problem, "a rename across two file systems must not be promised");
        assertFalse(Files.exists(target), "a refused rename must not leave half a file");
    }

    @Test
    void aCrossFileSystemRunRefusesThePromiseAndLandsInAnIndependentFile() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path fallback = tmp.resolve("fb");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, foreign, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")),
            "the diagnostic directory must stay empty when the promise is refused");
        Path landed = fallback.resolve(RUN).resolve("close-seal.tsv");
        assertTrue(Files.isRegularFile(landed), "the independent fallback file has to exist");
        String text = Files.readString(landed, StandardCharsets.UTF_8);
        assertEquals("cross-filesystem", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "fallback"));
        assertEquals("refused", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "primaryAtomic"));
        assertEquals(List.of(), CloseSealChecks.fallbackMarked(text));
        assertEquals("cross-filesystem", writer.failureReason());
    }

    @Test
    void aSymbolicLinkAtTheRunDirectoryIsRefused() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Path fallback = tmp.resolve("fb");
        Files.createSymbolicLink(diag.resolve(RUN), elsewhere);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("symlink-escape", writer.failureReason());
        assertFalse(Files.exists(elsewhere.resolve("close-seal.tsv")),
            "a link out of the diagnostic root must not receive the record");
        assertTrue(Files.isRegularFile(fallback.resolve(RUN).resolve("close-seal.tsv")));
    }

    @Test
    void theDiagnosticPathIsNormalisedBeforeItIsUsed() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("unused"));
        String value = root.resolve("unused").resolve("..").resolve("used").toString();
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(root.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), value);
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertTrue(Files.isRegularFile(root.resolve("used").resolve(RUN).resolve("close-seal.tsv")));
        assertFalse(Files.exists(root.resolve("unused").resolve(RUN)));
        assertEquals(root.resolve("used").resolve(RUN).toRealPath().toString(),
            Paths.get(writer.path()).getParent().toRealPath().toString());
    }

    @Test
    void aMissingOrRelativePropertyFallsBackWithItsOwnReason() throws IOException {
        Path fallback = tmp.resolve("fb");
        CloseSealWriter missing = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L, 1L, 1L)), null);
        entryAndTerminal(missing, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("diag-dir-property-missing", missing.failureReason());
        CloseSealWriter relative = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-relative"), null, 1000L, List.of(1L, 1L, 1L)),
            "relative/close-seal");
        entryAndTerminal(relative, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("diag-dir-not-absolute", relative.failureReason());
        String text = Files.readString(tmp.resolve("fb-relative").resolve(RUN)
            .resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.fallbackMarked(text));
        assertFalse(text.contains("fallback=-"), "a degraded file has to name its reason");
    }

    @Test
    void aFileWhereTheDirectoryShouldBeFallsBack() throws IOException {
        Path diag = tmp.resolve("diag");
        Files.writeString(diag, "not a directory\n");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("path-not-a-directory", writer.failureReason());
        assertTrue(Files.isRegularFile(tmp.resolve("fb").resolve(RUN).resolve("close-seal.tsv")));
    }

    @Test
    void anUnwritableDirectoryFallsBack() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("locked"));
        Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(diag),
            "this process may write a read-only directory, so the refusal cannot be shown");
        try {
            CloseSealWriter writer = new CloseSealWriter(RUN,
                limits(tmp.resolve("fb"), null, 400L, List.of(1L, 1L, 1L)), diag.toString());
            entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
            assertEquals("path-not-writable", writer.failureReason());
            assertTrue(Files.isRegularFile(tmp.resolve("fb").resolve(RUN).resolve("close-seal.tsv")));
        } finally {
            Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aFullDiskIsNamedFromTheFailureAndNotFromTheCallSite() {
        assertEquals("disk-full", CloseSealFiles.reason(new IOException("No space left on device")));
        assertEquals("path-not-writable",
            CloseSealFiles.reason(new IOException("Read-only file system")));
        assertEquals("path-not-a-directory", CloseSealFiles.reason(new IOException("Not a directory")));
        assertEquals("io-error", CloseSealFiles.reason(new IOException("something else")));
        assertEquals("atomic-move-unsupported", CloseSealFiles.reason(
            new java.nio.file.AtomicMoveNotSupportedException("a", "b", "different stores")));
        assertNotEquals("disk-full", CloseSealFiles.reason(new IOException("something else")));
    }

    @Test
    void aCapThatCannotHoldTheEntryRowWritesTheVerdictAlone() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 1, null,
                tmp.resolve("fb")), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C4"), CloseSealChecks.stages(text),
            "the entry row has no seat, so only the verdict can be written");
        String terminal = CloseSealChecks.dataLines(text).get(0);
        assertEquals("NOT_EXECUTABLE", CloseSealChecks.cell(terminal, "status"));
        assertEquals("C1_WRITE_FAILED", CloseSealChecks.field(terminal, "rowReason"));
        assertEquals("evidence", CloseSealChecks.field(terminal, "rowExit"));
        assertFalse(CloseSealChecks.oneEntry(text).isEmpty(),
            "a record with no entry row must never pass the pair check");
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertEquals("FILE_CAP_REACHED", writer.evidenceExit());
        assertTrue(String.join(";", writer.consoleHistory()).contains("FILE_CAP_REACHED"),
            "the console is the second path and has to carry the refusal");
    }

    @Test
    void aCappedRecordStillLeavesTheTerminalRecordThatNamesTheLoss() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 2, null,
                tmp.resolve("fb")), diag.toString());
        repeatEntryThenTerminal(writer);
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text),
            "the dropped repeat must not cost the terminal record its seat");
        String terminal = CloseSealChecks.dataLines(text).get(1);
        assertEquals("FAILED", CloseSealChecks.cell(terminal, "status"));
        assertEquals("FILE_CAP_REACHED", CloseSealChecks.field(terminal, "rowReason"));
        assertEquals("REACHED", CloseSealChecks.field(terminal, "cap"));
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals(List.of(), CloseSealChecks.preClosePostClose(text),
            "the record of a capped run still has to be a readable pair");
        assertEquals("FAILED", writer.status());
        assertEquals("FILE_CAP_REACHED", writer.reason());
        assertEquals("evidence", writer.exit());
    }

    @Test
    void aByteCapThatDropsARepeatStillLeavesTheTerminalRecord() throws IOException {
        Path probeDir = Files.createDirectories(tmp.resolve("probe"));
        CloseSealWriter probe = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-probe"), null, 1000L, List.of(1L)), probeDir.toString());
        probe.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        String probeText = Files.readString(probeDir.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        long row = probeText.length() - probeText.indexOf('\n') - 1L;
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L),
                2L * probeText.length() + 4096L, 4096, null, tmp.resolve("fb")), diag.toString());
        repeatEntryThenTerminal(writer);
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text),
            "the byte cap dropped the repeat, not the verdict");
        String terminal = CloseSealChecks.dataLines(text).get(1);
        assertEquals("FAILED", CloseSealChecks.cell(terminal, "status"));
        assertEquals("FILE_CAP_REACHED", CloseSealChecks.field(terminal, "rowReason"));
        assertTrue(terminal.length() <= row + 4096L,
            "the terminal record outgrew the seat the entry record left: " + terminal.length());
    }

    @Test
    void aRecordThatLostARowIsNeverReadAsComplete() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.preClosePostClose(text),
            "the clean copy has to pass the pair check");
        String[] lines = text.split("\n", -1);
        String truncated = lines[0] + "\n" + lines[1] + "\n";
        List<String> found = CloseSealChecks.preClosePostClose(truncated);
        assertFalse(found.isEmpty(),
            "a record whose terminal row is missing must never be read as complete");
        assertTrue(String.join(";", found).contains("incomplete"), found.toString());
    }

    @Test
    void aRunIdThatAlreadyHoldsARecordIsNeverReplaced() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = Files.createDirectories(diag.resolve(RUN));
        Path older = runDir.resolve("close-seal.tsv");
        Files.writeString(older, "OLD-SEAL\n", StandardCharsets.UTF_8);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("OLD-SEAL\n", Files.readString(older, StandardCharsets.UTF_8),
            "one step rename replaces its target, so the directory has to be refused before it");
        assertEquals(List.of("close-seal.tsv"), list(runDir),
            "nothing may be added beside the record of the earlier run");
        assertEquals("run-dir-exists", writer.failureReason());
        assertTrue(Files.isRegularFile(tmp.resolve("fb").resolve(RUN).resolve("close-seal.tsv")),
            "the second run has to land in the independent fallback file");
    }

    @Test
    void aRunIdHeldInBothRootsLeavesBothRecordsUntouched() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path fallback = Files.createDirectories(tmp.resolve("fb"));
        Path olderDiag = Files.createDirectories(diag.resolve(RUN)).resolve("close-seal.tsv");
        Path olderFallback = Files.createDirectories(fallback.resolve(RUN)).resolve("close-seal.tsv");
        Files.writeString(olderDiag, "OLD-DIAG\n", StandardCharsets.UTF_8);
        Files.writeString(olderFallback, "OLD-FB\n", StandardCharsets.UTF_8);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, null, 1000L, List.of(1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals("OLD-DIAG\n", Files.readString(olderDiag, StandardCharsets.UTF_8));
        assertEquals("OLD-FB\n", Files.readString(olderFallback, StandardCharsets.UTF_8));
        assertEquals("no-fallback-path", writer.failureReason());
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertTrue(writer.lastConsole().contains("run-dir-exists"),
            "the console has to name what held both roots: " + writer.lastConsole());
    }

    @Test
    void theErrorCellIsBoundedAndNamesWhatItDropped() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        for (int index = 0; index < CloseSealWriter.MAX_ERRORS_KEPT + 3; index++) {
            writer.errored(new IllegalStateException("error " + index));
        }
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        String cell = CloseSealChecks.cell(CloseSealChecks.dataLines(text).get(1), "error");
        assertTrue(cell.contains("errorsDropped=3"), cell);
        assertEquals(CloseSealWriter.MAX_ERRORS_KEPT, cell.split("continued=1").length - 1,
            "the cell keeps the bounded list and counts the rest");
    }

    @Test
    void theByteCapRefusesTheFirstRecord() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 200L, 4096, null,
                tmp.resolve("fb")), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")));
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertEquals("FILE_CAP_REACHED", writer.evidenceExit());
    }

    @Test
    void everyTerminalIsDistinctAndBooksTheSideThatFailed() throws IOException {
        List<String[]> seen = new ArrayList<>();
        seen.add(c1WriteFailed());
        seen.add(flushFailed());
        seen.add(closeRefused());
        seen.add(unrecordedItems());
        seen.add(c4WriteFailed());
        Set<String> pairs = new TreeSet<>();
        for (String[] item : seen) {
            pairs.add(item[0] + '/' + item[1]);
        }
        assertEquals(5, pairs.size(), "the five terminals have to be five different pairs: " + pairs);
        assertEquals("NOT_EXECUTABLE/C1_WRITE_FAILED", seen.get(0)[0] + '/' + seen.get(0)[1]);
        assertEquals("evidence", seen.get(0)[2]);
        assertEquals("FAILED/FLUSH_FAILED", seen.get(1)[0] + '/' + seen.get(1)[1]);
        assertEquals("core", seen.get(1)[2]);
        assertEquals("PARTIAL/CLOSE_REFUSED", seen.get(2)[0] + '/' + seen.get(2)[1]);
        assertEquals("core", seen.get(2)[2]);
        assertEquals("PARTIAL/UNRECORDED_ITEMS", seen.get(3)[0] + '/' + seen.get(3)[1]);
        assertEquals("core", seen.get(3)[2]);
        assertEquals("FAILED/C4_WRITE_FAILED", seen.get(4)[0] + '/' + seen.get(4)[1]);
        assertEquals("evidence", seen.get(4)[2]);
    }

    @Test
    void aHitDeadlineIsAStateAndDoesNotHoldTheCaller() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("blocked"));
        Files.createDirectories(diag.resolve(RUN).resolve("close-seal.tsv"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 5, List.of(100_000L, 100_000L, 100_000L, 100_000L,
                100_000L), 4L * 1024L * 1024L, 4096, null, tmp.resolve("fb")), diag.toString());
        long started = System.nanoTime();
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < 1500L, "a backoff far past the budget held the caller " + elapsed + " ms");
        assertEquals("NOT_EXECUTABLE", writer.status());
        assertEquals("C1_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertTrue(writer.lastConsole().contains("evidence"));
        assertEquals("C1_WRITE_FAILED+C4_WRITE_FAILED", writer.evidenceExit());
    }

    @Test
    void twoThreadsEnteringAtOnceStillWriteOneEntryRecord() throws Exception {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
                    CloseSealCriteriaTest.readings(PRE_FLUSH)));
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C1-DUP", "C4"), CloseSealChecks.stages(text));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        assertEquals(List.of(), CloseSealChecks.appendOnly(text));
        assertEquals(2, writer.eventOrdinal());
    }

    @Test
    void theWriterAndItsFileHelperNeverTakeAWorldOrAPlatformType() {
        assertEquals(List.of(), foreignTypes(CloseSealWriter.class));
        assertEquals(List.of(), foreignTypes(CloseSealFiles.class));
        assertEquals(List.of("net.minecraft.server.MinecraftServer"), foreignTypes(WorldProbe.class));
    }

    @Test
    void aModuleDrivenRunRendersTheRealReadings() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        KernelModule module = KernelModule.instance();
        writer.entry(WORLDS, module);
        writer.flushing();
        writer.closed(module.closeCommitChain());
        writer.terminal(WORLDS, module);
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        assertEquals(List.of(), CloseSealChecks.sealedIdentity(text));
        assertEquals(List.of(), CloseSealChecks.unrecordedNotFolded(text));
        assertEquals(List.of(), CloseSealChecks.statusColumn(text));
        assertEquals(List.of(), CloseSealChecks.deadline(text));
        String entry = CloseSealChecks.dataLines(text).get(0);
        assertTrue(CloseSealChecks.cell(entry, "ownerIdentity")
            .contains("product.commit.owner_thread_source=server.getRunningThread"));
        assertTrue(CloseSealChecks.cell(entry, "wiring").contains("closeOutcome="));
        assertTrue(CloseSealChecks.cell(entry, "sealed").contains("product.commit.seal_discards=0"));
        assertTrue(CloseSealChecks.cell(entry, "worldSet").contains("count=2"));
        assertTrue(CloseSealChecks.cell(entry, "unload").contains("minecraft:overworld=NEVER_RUN"));
    }

    @Test
    void theSealRecordsStayAsTheyWereWhileTheForensicGroupExists() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        KernelModule module = KernelModule.instance();
        writer.entry(WORLDS, module);
        writer.flushing();
        writer.closed(module.closeCommitChain());
        writer.terminal(WORLDS, module);
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        // The two captures read the shared rendering, and the group is rendered for the command
        // face alone: no key of it reaches a cell of the record and no cell is overwritten.
        Map<String, String> captured = CloseSealWriter.Snapshot.capture("C1", WORLDS, module)
            .readings();
        for (String key : new String[] {"product.commit.owner_thread_bound_id",
            "product.commit.declaration_seats", "product.commit.run_identity"}) {
            assertFalse(captured.containsKey(key), key + " reached the seal snapshot");
            assertFalse(text.contains(key), key + " reached the seal record");
        }
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text));
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals(CloseSealWriter.COLUMNS.length,
            CloseSealChecks.header(text).split("\t", -1).length);
        assertEquals(2, writer.writeSeq());
        // The command face is where the group is published, beside the rendering it shares.
        List<String> shared = KernelReadings.export(module);
        List<String> forensic = KernelReadings.forensicReadings(module);
        assertEquals(keys(shared), keys(forensic.subList(0, shared.size())),
            "the shared rendering keeps its keys inside the command rendering");
        assertTrue(forensic.size() > shared.size());
        assertTrue(String.join("\n", forensic).contains("product.commit.declaration_seats"));
    }

    @Test
    void theHappyPathCostsWellUnderTheBudget() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        long started = System.nanoTime();
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsed < 200L, "the happy path took " + elapsed + " ms");
        assertEquals("OK", writer.status());
        assertEquals("NONE", writer.reason());
        assertEquals("core", writer.exit());
        assertEquals(2, writer.writeSeq());
    }

    @Test
    void theFirstRecordOfANewRunDirectoryAlsoWritesItsIncrementHash() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        assertFalse(Files.exists(runDir), "this test is only about a run directory that is not there yet");
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> rows = CloseSealChecks.dataLines(text);
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text));
        assertEquals(List.of("close-seal.md5", "close-seal.tsv"), list(runDir),
            "the run directory holds the record and its increment hash, and no staged file");
        String first = CloseSealChecks.header(text) + '\n' + rows.get(0) + '\n';
        String second = rows.get(1) + '\n';
        assertEquals(first + second, text, "the record file is exactly the two written records");
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(2, receipts.size(), "one receipt line per record");
        assertEquals(CloseSealWriter.md5(first) + "  close-seal.tsv  bytes="
            + first.getBytes(StandardCharsets.UTF_8).length + "  writeSeq=1", receipts.get(0),
            "the first receipt has to be recomputable after the terminal record was appended");
        assertEquals(CloseSealWriter.md5(text) + "  close-seal.tsv  bytes="
            + text.getBytes(StandardCharsets.UTF_8).length + "  writeSeq=2", receipts.get(1),
            "the second line describes the whole file, not the text of the second record");
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        assertEquals("0", CloseSealChecks.field(rows.get(0), "receiptFailures"));
        assertEquals("-", CloseSealChecks.field(rows.get(0), "receiptError"));
        assertEquals("0", CloseSealChecks.field(rows.get(1), "receiptFailures"));
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals("OK", writer.status());
        assertEquals("NONE", writer.reason());
        assertEquals("core", writer.exit());
    }

    @Test
    void aFreshReceiptFileIsRefusedByTheOldOpenOptionsAndStartedByTheShippedOnes() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("historical"));
        Path fresh = dir.resolve("close-seal.md5");
        assertThrows(NoSuchFileException.class, () -> FileChannel.open(fresh,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND),
            "write and append without create is what left the increment hash unwritten");
        assertEquals("", CloseSealFiles.append(fresh,
            "aaaa  close-seal.tsv  bytes=1  writeSeq=1\n"));
        assertTrue(Files.isRegularFile(fresh), "the shipped path has to start the file itself");
        assertEquals("", CloseSealFiles.append(fresh,
            "bbbb  close-seal.tsv  bytes=2  writeSeq=2\n"));
        assertEquals(List.of("aaaa  close-seal.tsv  bytes=1  writeSeq=1",
            "bbbb  close-seal.tsv  bytes=2  writeSeq=2"),
            Files.readAllLines(fresh, StandardCharsets.UTF_8),
            "the second append must not replace what the first one wrote");
    }

    @Test
    void aReceiptThatCannotBeWrittenIsNamedOnTheTerminalRecordAndNeverDropped() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = Files.createDirectories(diag.resolve(RUN));
        Files.createDirectories(runDir.resolve("close-seal.md5"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> rows = CloseSealChecks.dataLines(text);
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text),
            "a refused receipt must not cost the terminal record that names it");
        String terminal = rows.get(1);
        assertEquals("FAILED", CloseSealChecks.cell(terminal, "status"));
        assertEquals("C4_WRITE_FAILED", CloseSealChecks.field(terminal, "rowReason"));
        assertEquals("evidence", CloseSealChecks.field(terminal, "rowExit"));
        assertEquals("1", CloseSealChecks.field(terminal, "receiptFailures"));
        assertEquals("io-error", CloseSealChecks.field(terminal, "receiptError"),
            "the receipt target is a directory, and the reason scale has no name of its own for it");
        assertTrue(CloseSealChecks.field(terminal, "evidenceExit").contains("C4_WRITE_FAILED"));
        assertEquals(List.of(), CloseSealChecks.columnsAndHashes(text));
        assertEquals(List.of(), CloseSealChecks.terminals(text),
            "the terminal still has to be inside the closed sets, not a name of its own");
        String console = String.join(";", writer.consoleHistory());
        assertTrue(console.contains("receipt=io-error"), console);
        assertTrue(console.contains("reason=C4_WRITE_FAILED"), console);
        assertEquals("FAILED", writer.status());
        assertEquals("C4_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
    }

    @Test
    void theEntryIncrementLineDescribesTheWholeRecordFileAtThatStage() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        String first = CloseSealChecks.header(text) + '\n' + CloseSealChecks.dataLines(text).get(0)
            + '\n';
        long length = first.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(first, text, "the entry record is the whole file at that stage");
        assertEquals(1, receipts.size());
        assertEquals(CloseSealWriter.md5(first) + "  close-seal.tsv  bytes=" + length
            + "  writeSeq=1", receipts.get(0));
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        List<String> wrongDigest = new ArrayList<>(receipts);
        wrongDigest.set(0, receipts.get(0).replace(CloseSealWriter.md5(first), "0".repeat(32)));
        assertNotEquals(receipts.get(0), wrongDigest.get(0));
        assertFalse(CloseSealChecks.receiptSnapshots(text, wrongDigest).isEmpty(),
            "a digest that does not recompute has to be named");
        List<String> shortByOne = new ArrayList<>(receipts);
        shortByOne.set(0, receipts.get(0).replace("bytes=" + length, "bytes=" + (length - 1)));
        assertNotEquals(receipts.get(0), shortByOne.get(0));
        assertFalse(CloseSealChecks.receiptSnapshots(text, shortByOne).isEmpty(),
            "a length that names a shorter prefix has to be named");
    }

    @Test
    void theTerminalIncrementLineDescribesTheFinalFileAndKeepsTheEntryDigestRecomputable()
        throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        assertEquals(2, receipts.size());
        List<String> rows = CloseSealChecks.dataLines(text);
        String entry = CloseSealChecks.header(text) + '\n' + rows.get(0) + '\n';
        assertEquals(CloseSealWriter.md5(entry) + "  close-seal.tsv  bytes="
            + entry.getBytes(StandardCharsets.UTF_8).length + "  writeSeq=1", receipts.get(0),
            "the entry digest has to stay recomputable from the prefix the file kept");
        assertEquals(CloseSealWriter.md5(text) + "  close-seal.tsv  bytes="
            + text.getBytes(StandardCharsets.UTF_8).length + "  writeSeq=2", receipts.get(1),
            "the terminal line describes the whole file, not the text of its own record");
        String terminalText = rows.get(1) + '\n';
        assertNotEquals(CloseSealWriter.md5(terminalText), receipts.get(1).split("\\s+")[0]);
        List<String> asTheThirdRunWroteIt = List.of(receipts.get(0), CloseSealWriter.md5(terminalText)
            + "  close-seal.tsv  bytes=" + text.getBytes(StandardCharsets.UTF_8).length
            + "  writeSeq=2");
        assertFalse(CloseSealChecks.receiptSnapshots(text, asTheThirdRunWroteIt).isEmpty(),
            "the increment line the third run wrote has to fail the criterion that replaces it");
    }

    @Test
    void theSecondRecordAppendsAndLeavesTheEntryIncrementLineAndItsPrefixUntouched()
        throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        Path file = runDir.resolve("close-seal.tsv");
        Path increment = runDir.resolve("close-seal.md5");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        String afterEntry = Files.readString(file, StandardCharsets.UTF_8);
        List<String> linesAfterEntry = Files.readAllLines(increment, StandardCharsets.UTF_8);
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String afterTerminal = Files.readString(file, StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(increment, StandardCharsets.UTF_8);
        assertTrue(afterTerminal.startsWith(afterEntry), "the second record appends");
        assertTrue(afterTerminal.length() > afterEntry.length());
        assertEquals(1, linesAfterEntry.size());
        assertEquals(linesAfterEntry.get(0), receipts.get(0),
            "the entry increment line survives the terminal record");
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(afterTerminal, receipts));
        assertEquals(List.of(), CloseSealChecks.entryNotReplaced(afterTerminal));
        assertEquals(List.of(), CloseSealChecks.appendOnly(afterTerminal));
        String replaced = CloseSealCriteriaTest.editCell(afterTerminal, 0, "stage", "C4");
        assertFalse(CloseSealChecks.entryNotReplaced(replaced).isEmpty(),
            "a copy whose entry record was replaced has to be named");
        List<String> droppedEntryLine = new ArrayList<>(receipts);
        droppedEntryLine.remove(0);
        assertFalse(CloseSealChecks.receiptSnapshots(afterTerminal, droppedEntryLine).isEmpty(),
            "a copy of the increment file that lost its first line has to be named");
    }

    @Test
    void aRepeatedEntryKeepsItsStageAndWritesItsOwnIncrementSnapshot() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        repeatEntryThenTerminal(writer);
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C1-DUP", "C4"), CloseSealChecks.stages(text));
        assertEquals(3, receipts.size(), "one increment line per record, the repeat included");
        assertEquals("repeated-entry", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(1),
            "note"));
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        assertEquals(List.of(), CloseSealChecks.oneEntry(text));
        List<String> withoutTheRepeat = List.of(receipts.get(0), receipts.get(2));
        assertFalse(CloseSealChecks.receiptSnapshots(text, withoutTheRepeat).isEmpty(),
            "a repeat record without its own increment line has to be named");
        String renamed = CloseSealCriteriaTest.editCell(text, 1, "stage", "C1");
        assertFalse(CloseSealChecks.oneEntry(renamed).isEmpty(),
            "a second record named as the entry record is still not allowed");
        String unreasoned = CloseSealCriteriaTest.editField(text, 1, "note", "-");
        assertFalse(CloseSealChecks.oneEntry(unreasoned).isEmpty(),
            "a repeat without a reason is still not allowed");
    }

    @Test
    void aTerminalRecordThatCannotBeAppendedIsNamedAndLeavesAnIncrementAboutTheFileThatRemains()
        throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        Path file = runDir.resolve("close-seal.tsv");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        String before = Files.readString(file, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        Assumptions.assumeFalse(Files.isWritable(file),
            "this process may write a read-only file, so the refusal cannot be shown");
        try {
            writer.flushing();
            writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
                CloseSealCriteriaTest.readings(POST_FLUSH)));
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(before, text, "a refused terminal record must not touch the entry record");
        assertEquals(1, receipts.size(), "a record that was refused has no increment line");
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        assertEquals("FAILED", writer.status());
        assertEquals("C4_WRITE_FAILED", writer.reason());
        assertEquals("evidence", writer.exit());
        assertTrue(writer.lastConsole().contains("C4_WRITE_FAILED"), writer.lastConsole());
    }

    @Test
    void aCappedRunWritesOneIncrementLinePerRecordItKeptAndNamesTheLoss() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            new CloseSealWriter.Limits(1000L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 2, null,
                tmp.resolve("fb")), diag.toString());
        repeatEntryThenTerminal(writer);
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(List.of("C1", "C4"), CloseSealChecks.stages(text),
            "the row cap drops the repeat and keeps the verdict");
        assertEquals(2, receipts.size(), "a record that was never written has no increment line");
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        String terminal = CloseSealChecks.dataLines(text).get(1);
        assertEquals("FAILED", CloseSealChecks.cell(terminal, "status"));
        assertEquals("FILE_CAP_REACHED", CloseSealChecks.field(terminal, "rowReason"));
        assertEquals("REACHED", CloseSealChecks.field(terminal, "cap"));
        List<String> withAPhantomLine = new ArrayList<>(receipts);
        withAPhantomLine.add(receipts.get(1));
        assertFalse(CloseSealChecks.receiptSnapshots(text, withAPhantomLine).isEmpty(),
            "an increment line for a record the file does not carry has to be named");
        String lostRow = CloseSealCriteriaTest.dropRow(text, 1);
        assertFalse(CloseSealChecks.receiptSnapshots(lostRow, receipts).isEmpty(),
            "a file that lost its terminal row can no longer carry two increment lines");
        assertFalse(CloseSealChecks.preClosePostClose(lostRow).isEmpty());
    }

    @Test
    void aRunLeavesNoStagedFileInTheLandingDirectoryOrInTheStagingStore() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path runDir = diag.resolve(RUN);
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb"), null, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals(List.of("close-seal.md5", "close-seal.tsv"), list(runDir));
        assertEquals(List.of(RUN), list(diag), "no staged file may survive beside the run directory");
        assertEquals(List.of(), staged(diag));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path fallback = tmp.resolve("fb-residue");
        CloseSealWriter crossed = new CloseSealWriter(RUN,
            limits(fallback, foreign, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(crossed, new KernelModule.CommitClose(true, "", 0, 0, 0));
        assertEquals(List.of("close-seal.md5", "close-seal.tsv"), list(fallback.resolve(RUN)));
        assertEquals(List.of(), staged(foreign), "the staging store may not keep a staged file");
    }

    @Test
    void aCrossFileSystemRunLandsItsRecordAndItsIncrementInTheIndependentFile() throws IOException {
        Assumptions.assumeTrue(CloseSealFiles.sameFileStore(tmp, Paths.get("/tmp")),
            "the temporary root is not /tmp on this machine, so this is not that pair");
        Path diag = Files.createDirectories(tmp.resolve("diag"));
        Path foreign = foreignStore(diag);
        Assumptions.assumeTrue(foreign != null, "no second file system to stage across");
        Path fallback = tmp.resolve("fb");
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(fallback, foreign, 1000L, List.of(1L, 1L, 1L)), diag.toString());
        entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
        Path runDir = fallback.resolve(RUN);
        assertEquals(List.of(), list(diag.resolve(RUN)),
            "the diagnostic side holds no file when the atomic promise is refused");
        String text = Files.readString(runDir.resolve("close-seal.tsv"), StandardCharsets.UTF_8);
        List<String> receipts = Files.readAllLines(runDir.resolve("close-seal.md5"),
            StandardCharsets.UTF_8);
        assertEquals(List.of(), CloseSealChecks.receiptSnapshots(text, receipts));
        assertEquals(2, receipts.size());
        assertEquals("refused", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "primaryAtomic"));
        assertEquals("cross-filesystem", CloseSealChecks.field(CloseSealChecks.dataLines(text).get(0),
            "fallback"));
        assertEquals(CloseSealWriter.md5(text) + "  close-seal.tsv  bytes="
            + text.getBytes(StandardCharsets.UTF_8).length + "  writeSeq=2", receipts.get(1));
        assertEquals(List.of(), staged(foreign));
        System.out.println("CROSS_FILE_SYSTEM\t" + foreign + "\t" + Files.getFileStore(foreign)
            + "\tlanded=" + runDir);
    }

    @Test
    void aRecordFileThatCannotBeReadIsNamedInsteadOfAnsweredWithANumber() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("unreadable"));
        Path asDirectory = Files.createDirectories(dir.resolve("close-seal.tsv"));
        CloseSealFiles.Whole whole = CloseSealFiles.Whole.of(asDirectory);
        assertFalse(whole.read(), "a directory is not a record file");
        assertEquals(0, whole.bytes().length);
        assertNotEquals("", whole.problem());
        assertFalse(CloseSealFiles.Whole.of(dir.resolve("missing.tsv")).read());
        assertNotEquals("", CloseSealFiles.Whole.of(dir.resolve("missing.tsv")).problem());
    }

    private static List<String> keys(List<String> lines) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf('=');
            found.add(at > 0 ? line.substring(0, at) : line);
        }
        return found;
    }

    private String[] c1WriteFailed() throws IOException {
        Path blocked = Files.createDirectories(tmp.resolve("blocked-c1"));
        Path diag = Files.createDirectories(blocked.resolve("diag"));
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("r-x------"));
        Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("r-x------"));
        Assumptions.assumeFalse(Files.isWritable(blocked) || Files.isWritable(diag),
            "this process may write a read-only directory, so no failure can be forced");
        try {
            CloseSealWriter writer = new CloseSealWriter(RUN,
                new CloseSealWriter.Limits(300L, 3, List.of(1L, 1L, 1L), 4L * 1024L * 1024L, 4096,
                    null, blocked.resolve("fb")), diag.toString());
            entryAndTerminal(writer, new KernelModule.CommitClose(true, "", 0, 0, 0));
            assertEquals("no-fallback-path", writer.failureReason());
            assertFalse(Files.exists(diag.resolve(RUN).resolve("close-seal.tsv")));
            return new String[] {writer.status(), writer.reason(), writer.exit()};
        } finally {
            Files.setPosixFilePermissions(diag, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private String[] flushFailed() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("flush-failed"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-flush"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.errored(new IllegalStateException("the world loop threw"));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        assertTrue(CloseSealChecks.cell(CloseSealChecks.dataLines(text).get(1), "error")
            .contains("IllegalStateException"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] closeRefused() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("close-refused"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-close"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(false, "off-owner-thread", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] unrecordedItems() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("unrecorded"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-unrecorded"), null, 1000L, List.of(1L)), diag.toString());
        Map<String, String> readings = CloseSealCriteriaTest.readings(POST_FLUSH);
        readings.put("product.commit.close_unrecorded", "2");
        readings.put("product.commit.chain_sealed", "1");
        readings.put("product.commit.chain_sealed_count", "2");
        readings.put("product.commit.chain_sealed_identities",
            "11:sealed-at-close:-:owed:1,12:sealed-at-close:-:owed:1");
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 2, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS, readings));
        String text = Files.readString(diag.resolve(RUN).resolve("close-seal.tsv"),
            StandardCharsets.UTF_8);
        String terminal = CloseSealChecks.dataLines(text).get(1);
        assertTrue(CloseSealChecks.cell(terminal, "unrecorded").contains("close_unrecorded=2"));
        assertTrue(CloseSealChecks.cell(terminal, "sealed")
            .contains("product.commit.chain_sealed_count=2"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private String[] c4WriteFailed() throws IOException {
        Path diag = Files.createDirectories(tmp.resolve("c4-failed"));
        CloseSealWriter writer = new CloseSealWriter(RUN,
            limits(tmp.resolve("fb-c4"), null, 1000L, List.of(1L)), diag.toString());
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        Path file = diag.resolve(RUN).resolve("close-seal.tsv");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        Assumptions.assumeFalse(Files.isWritable(file),
            "this process may write a read-only file, so the refusal cannot be shown");
        try {
            writer.flushing();
            writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
                CloseSealCriteriaTest.readings(POST_FLUSH)));
            assertEquals(before, Files.readString(file, StandardCharsets.UTF_8),
                "a refused terminal record must not touch the entry record");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        assertTrue(writer.lastConsole().contains("C4_WRITE_FAILED"));
        return new String[] {writer.status(), writer.reason(), writer.exit()};
    }

    private void repeatEntryThenTerminal(CloseSealWriter writer) {
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(new KernelModule.CommitClose(true, "", 0, 0, 0));
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
    }

    private void entryAndTerminal(CloseSealWriter writer, KernelModule.CommitClose close) {
        writer.entry(new CloseSealWriter.Snapshot("C1", WORLDS,
            CloseSealCriteriaTest.readings(PRE_FLUSH)));
        writer.flushing();
        writer.closed(close);
        writer.terminal(new CloseSealWriter.Snapshot("C4", WORLDS,
            CloseSealCriteriaTest.readings(POST_FLUSH)));
    }

    private CloseSealWriter.Limits limits(Path fallbackRoot, Path staging, long budget,
                                          List<Long> backoff) {
        return new CloseSealWriter.Limits(budget, 3, backoff, 4L * 1024L * 1024L, 4096, staging,
            fallbackRoot);
    }

    private static List<String> list(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(String::compareTo);
        return names;
    }

    /** The staged neighbours the atomic replace writes through, so a rename that did not finish
     * is a reading and never something a later look has to guess at. */
    private static List<String> staged(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (name.startsWith(".close-seal-")) {
                    names.add(name);
                }
            });
        }
        names.sort(String::compareTo);
        return names;
    }

    private static Path foreignStore(Path local) {
        for (String candidate : List.of("/dev/shm", "/run")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path) && !CloseSealFiles.sameFileStore(path, local)) {
                return path;
            }
        }
        return null;
    }

    private static List<String> foreignTypes(Class<?> owner) {
        List<String> found = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            found.addAll(foreign(method.getReturnType()));
            for (Class<?> parameter : method.getParameterTypes()) {
                found.addAll(foreign(parameter));
            }
        }
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                found.addAll(foreign(parameter));
            }
        }
        for (Field field : owner.getDeclaredFields()) {
            found.addAll(foreign(field.getType()));
        }
        found.sort(String::compareTo);
        return found;
    }

    private static List<String> foreign(Class<?> type) {
        List<String> found = new ArrayList<>();
        String name = type.getName();
        if (name.startsWith("net.minecraft.") || name.startsWith("org.bukkit.")) {
            found.add(name);
        }
        return found;
    }

    /** The negative control of the scan above: one method that does take a platform type. */
    static final class WorldProbe {

        void visit(MinecraftServer server) {
            if (server != null) {
                throw new IllegalStateException("never called");
            }
        }
    }
}
