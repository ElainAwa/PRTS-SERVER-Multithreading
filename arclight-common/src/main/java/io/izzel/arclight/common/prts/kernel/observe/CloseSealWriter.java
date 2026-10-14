/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** Writes what the stopping path can no longer be asked about. The entry record is copied from a
 * frozen in-memory map before any world is touched, the terminal record is copied after the flush
 * and the close, and every record is appended, so a later stage never replaces an earlier one. The
 * writer reads no world and waits for nothing, and all of its attempts share one budget of one
 * second, so a diagnostic that cannot be written cannot hold a shutdown open. */
public final class CloseSealWriter {

    /** The record set of one run. Version 2 keeps the column set of version 1 and changes only
     * what the two numbers of an increment line describe: from version 2 on, a line's digest and
     * its length are read from the same bytes of the record file as it stood after that stage, so
     * every line describes one object and either number recomputes from the file. A line of a
     * version 1 file describes the text of its own stage for every line after the first, which is
     * why a version 1 file has to be read with that rule and not this one. */
    static final String SCHEMA = "prts-close-seal/2";
    static final String DIAG_DIR_PROPERTY = "prts.diag.dir";
    static final String SOURCE = "event:ServerStoppingEvent";
    static final String OWNER = KernelModule.HOST_OWNER_THREAD_SOURCE;
    static final long HARD_BUDGET_MILLIS = 1000L;
    static final int MAX_RUNS_KEPT = 4;
    static final int MAX_CONSOLE_LINES = 16;
    static final int MAX_ERRORS_KEPT = 8;
    static final int TERMINAL_SEAT_BYTES = 4096;
    static final String NOT_WIRED = "NOT_WIRED";

    static final String[] COLUMNS = {"schema", "runId", "writeSeq", "eventOrdinal", "stage", "source",
        "owner", "epoch", "chainInFlight", "pending", "unrecorded", "sealed", "partial", "error",
        "flush", "unload", "reclaimToken", "arena", "reservation", "wiring", "ownerIdentity",
        "worldSet", "receipt", "status", "rowHash"};

    static final String[] PHASES = {"C1_NOT_WRITTEN", "C1_WRITTEN", "FLUSHING", "AFTER_CLOSE",
        "C4_NOT_WRITTEN"};

    static final String[] STATUSES = {"OK", "PARTIAL", "FAILED", "NOT_EXECUTABLE"};
    static final String[] REASONS = {"NONE", "C1_WRITE_FAILED", "FLUSH_FAILED", "CLOSE_REFUSED",
        "UNRECORDED_ITEMS", "PARTIAL_FLUSH", "C4_WRITE_FAILED", "FILE_CAP_REACHED"};
    static final String[] EVIDENCE_EXITS = {"NONE", "C1_WRITE_FAILED", "C4_WRITE_FAILED",
        "FILE_CAP_REACHED"};

    /** The key the delivery family answers on, and the value that says this process records the
     * unload deliveries of the worlds its host unloads. The cell below reads it and claims no
     * delivery of its own. */
    private static final String UNLOAD_WIRING_KEY = "product.unload.wiring";
    private static final String UNLOAD_WIRED = "WIRED";

    private static final String[] G_EPOCH = {"write.world_epochs_tracked", "write.world_epochs_live",
        "write.world_epochs_changes"};
    private static final String[] G_IN_FLIGHT = {"product.commit.chain_reserved",
        "product.commit.chain_finalized", "product.commit.chain_released",
        "product.commit.chain_in_flight", "product.commit.chain_conservation",
        "product.commit.chain_capacity_reserved", "product.commit.chain_capacity_finalized",
        "product.commit.chain_capacity_released"};
    private static final String[] G_PENDING = {"product.commit.pending_dispositions",
        "product.commit.pending", "product.commit.attention"};
    private static final String[] G_SEALED = {"product.commit.chain_sealed",
        "product.commit.chain_sealed_count", "product.commit.chain_sealed_turn",
        "product.commit.chain_sealed_identities", "product.commit.seal_discards"};
    private static final String[] G_FLUSH = {"product.commit.stop_flush_sessions",
        "product.commit.stop_flush_worlds", "product.commit.stop_flush_rows",
        "product.commit.stop_flush_steps", "product.commit.stop_flush_ordered",
        "product.commit.stop_flush_order", "product.commit.stop_flush_refusals",
        "product.commit.stop_flush_refusal"};
    private static final String[] G_RECLAIM = {"intent.queue_depth", "intent.released"};
    private static final String[] G_ARENA = {"arena.claims", "arena.releases", "arena.pinned",
        "arena.generation_bumps", "arena.stale_releases", "arena.repeat_releases",
        "arena.quarantined_slots", "arena.pin_pairs_hold"};
    private static final String[] G_RESERVATION = {"product.commit.state_entries",
        "product.commit.finished_entries", "product.commit.ledger_evicted",
        "product.commit.terminal_ledger_entries", "product.commit.terminal_ledger",
        "product.commit.effect_unresolved_terminals"};
    private static final String[] G_WIRING = {"product.commit.controller_wiring",
        "product.commit.close_entry", "product.commit.binding_refusal",
        "product.commit.close_calls"};
    private static final String[] G_OWNER = {"product.commit.owner_thread_source",
        "product.commit.owner_thread_bound", "product.commit.owner_thread_id",
        "product.commit.owner_thread_named", "product.commit.owner_thread_mismatches"};

    private static final char TAB = '\t';
    private static final char LF = '\n';

    private static final ConcurrentHashMap<String, CloseSealWriter> RUNS = new ConcurrentHashMap<>();
    private static final Instant STARTED_AT = Instant.now();
    private static final String RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault()).format(STARTED_AT)
        + '-' + ProcessHandle.current().pid();

    /** The identity of the run this process records, as one space separated text: the run id the
     * seal rows carry, the process id it was made with and the instant the class was initialized.
     * That instant is a lower bound on the process lifetime and never the process start, and all
     * three belong to the run that reads them rather than to the class. */
    static String runIdentity() {
        return RUN_ID + " " + ProcessHandle.current().pid() + " " + STARTED_AT.toEpochMilli();
    }

    /** One frozen reading set and the world list read beside it. */
    record Snapshot(String stage, List<String> worlds, Map<String, String> readings) {

        static Snapshot capture(String stage, List<String> worlds, KernelModule module) {
            Map<String, String> readings = new LinkedHashMap<>();
            for (String line : KernelReadings.export(module)) {
                int at = line.indexOf('=');
                if (at > 0) {
                    readings.put(line.substring(0, at), line.substring(at + 1));
                }
            }
            return new Snapshot(stage, List.copyOf(worlds), readings);
        }

        String at(String key) {
            return readings.getOrDefault(key, "-");
        }
    }

    /** What one entry into the listener may spend, and where it stages and falls back to. */
    public record Limits(long budgetMillis, int maxRetries, List<Long> backoffMillis, long maxBytes,
                         int maxRows, Path tmpDir, Path fallbackRoot) {

        public static Limits defaults() {
            return new Limits(HARD_BUDGET_MILLIS, 3, List.of(50L, 200L, 500L), 4L * 1024L * 1024L,
                4096, null, null);
        }
    }

    /** The writer of this process, reused by every entry so one run holds exactly one entry record. */
    public static CloseSealWriter forRun() {
        if (RUNS.size() >= MAX_RUNS_KEPT) {
            RUNS.clear();
        }
        return RUNS.computeIfAbsent(RUN_ID,
            id -> new CloseSealWriter(id, Limits.defaults(), System.getProperty(DIAG_DIR_PROPERTY)));
    }

    private final String runId;
    private final Limits limits;
    private final String diagDirValue;
    private final AtomicInteger entries = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final List<String> phaseTrace = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> consoleLines = new ArrayList<>();

    private int writeSeq;
    private int receiptFailures;
    private String receiptError = "";
    private int errorsDropped;
    private long rows;
    private long bytes;
    private long startedNanos;
    private long deadlineNanos;
    private boolean deadlineHit;
    private boolean capReached;
    private int retries;
    private boolean entryWritten;
    private boolean entryAttempted;
    private String flushError = "";
    private String closeOutcome = "-";
    private String worldSetAtEntry = "-";
    private String fallbackReason = "";
    private String primaryAtomic = "promised";
    private String mode = "ATOMIC_MOVE";
    private Path resolved;
    private Path stagingDir;
    private boolean resolutionFailed;
    private String status = "NOT_EXECUTABLE";
    private String reason = "NONE";
    private String exit = "evidence";
    private String evidenceExit = "NONE";
    private String lastConsole = "-";

    CloseSealWriter(String runId, Limits limits, String diagDirValue) {
        this.runId = runId;
        this.limits = limits;
        this.diagDirValue = diagDirValue;
    }

    /** The entry record: copied before any world is touched, and copied once per run. */
    public void entry(List<String> worlds, KernelModule module) {
        entry(Snapshot.capture("C1", worlds, module));
    }

    /** The world loop is starting; nothing after this point is still the entry state. */
    public void flushing() {
        note("FLUSHING");
    }

    /** What the close answered, so a refusal and an unwired chain stay two different readings. */
    public void closed(KernelModule.CommitClose close) {
        closeOutcome = close == null ? "-" : (close.closed() ? "closed" : close.refusal());
        note("AFTER_CLOSE");
    }

    /** Records an exception of the stopping path without swallowing it. The list is bounded so the
     * terminal record cannot outgrow the seat the entry record left for it; what does not fit is
     * counted rather than dropped in silence. */
    public void errored(Throwable error) {
        flushError = error.getClass().getName();
        if (errors.size() >= MAX_ERRORS_KEPT) {
            errorsDropped++;
            return;
        }
        errors.add("phase=" + currentPhase() + "|type=" + error.getClass().getName() + "|message="
            + firstLine(error.getMessage()) + "|continued=1");
    }

    /** The terminal record: written whether the steps above succeeded or failed. */
    public void terminal(List<String> worlds, KernelModule module) {
        terminal(Snapshot.capture("C4", worlds, module));
    }

    public String runId() {
        return runId;
    }

    public String status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public String exit() {
        return exit;
    }

    public String evidenceExit() {
        return evidenceExit;
    }

    public String lastConsole() {
        return lastConsole;
    }

    public List<String> consoleHistory() {
        return List.copyOf(consoleLines);
    }

    public String phaseTrace() {
        return String.join(">", phaseTrace);
    }

    public String path() {
        return resolved == null ? "-" : resolved.toAbsolutePath().toString();
    }

    public int writeSeq() {
        return writeSeq;
    }

    public int eventOrdinal() {
        return entries.get();
    }

    public String failureReason() {
        return fallbackReason.isEmpty() ? "-" : fallbackReason;
    }

    public boolean deadlineHit() {
        return deadlineHit;
    }

    void entry(Snapshot snapshot) {
        if (!acquire()) {
            noteEvidence("C1_WRITE_FAILED");
            return;
        }
        try {
            opened();
            phaseTrace.clear();
            entries.incrementAndGet();
            note("C1_NOT_WRITTEN");
            worldSetAtEntry = worldSet(snapshot.worlds());
            boolean first = !entryAttempted;
            entryAttempted = true;
            String stage = first ? "C1" : "C1-DUP";
            boolean written = write(stage, snapshot, first ? "" : "repeated-entry", "-", "NONE", "-");
            if (first) {
                entryWritten = written;
            }
            note("C1_WRITTEN");
        } finally {
            lock.unlock();
        }
    }

    void terminal(Snapshot snapshot) {
        if (!acquire()) {
            return;
        }
        try {
            opened();
            note("C4_NOT_WRITTEN");
            String[] verdict = verdict(snapshot);
            boolean written = write("C4", snapshot, "", verdict[0], verdict[1], verdict[2]);
            if (!written && entryWritten) {
                noteEvidence("C4_WRITE_FAILED");
                status = "FAILED";
                reason = "C4_WRITE_FAILED";
                exit = "evidence";
                console("status=" + status + " reason=" + reason + " exit=" + exit);
            }
        } finally {
            lock.unlock();
        }
    }

    private void opened() {
        if (startedNanos == 0L) {
            startedNanos = System.nanoTime();
            deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(budget());
            deadlineHit = false;
        }
    }

    private boolean acquire() {
        try {
            if (lock.tryLock(budget(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            deadlineHit = true;
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            deadlineHit = true;
            return false;
        }
    }

    private long budget() {
        return Math.max(1L, Math.min(HARD_BUDGET_MILLIS, limits.budgetMillis()));
    }

    private void note(String phase) {
        phaseTrace.add(phase + '@' + Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private String currentPhase() {
        if (phaseTrace.isEmpty()) {
            return PHASES[0];
        }
        String last = phaseTrace.get(phaseTrace.size() - 1);
        int at = last.indexOf('@');
        return at < 0 ? last : last.substring(0, at);
    }

    private void noteEvidence(String name) {
        if (evidenceExit.contains(name)) {
            return;
        }
        evidenceExit = "NONE".equals(evidenceExit) ? name : evidenceExit + '+' + name;
    }

    /** The four-value status, the distinct terminal name and which side failed: a business step or
     * the diagnostic exit itself. */
    private String[] verdict(Snapshot snapshot) {
        String result;
        if (!entryWritten) {
            result = "NOT_EXECUTABLE|C1_WRITE_FAILED|evidence";
        } else if (!flushError.isEmpty()) {
            result = "FAILED|FLUSH_FAILED|core";
        } else if (closeRefused(snapshot)) {
            result = "PARTIAL|CLOSE_REFUSED|core";
        } else if (unrecorded(snapshot) > 0) {
            result = "PARTIAL|UNRECORDED_ITEMS|core";
        } else if (partialFlush(snapshot)) {
            result = "PARTIAL|PARTIAL_FLUSH|core";
        } else if (receiptFailures > 0) {
            result = "FAILED|C4_WRITE_FAILED|evidence";
        } else if (capReached) {
            result = "FAILED|FILE_CAP_REACHED|evidence";
        } else {
            result = "OK|NONE|core";
        }
        String[] parts = result.split("\\|");
        status = parts[0];
        reason = parts[1];
        exit = parts[2];
        return parts;
    }

    private boolean closeRefused(Snapshot snapshot) {
        if (NOT_WIRED.equals(snapshot.at("product.commit.controller_wiring"))) {
            return false;
        }
        return !"closed".equals(closeOutcome);
    }

    private int unrecorded(Snapshot snapshot) {
        if (NOT_WIRED.equals(snapshot.at("product.commit.controller_wiring"))) {
            return 0;
        }
        try {
            return Integer.parseInt(snapshot.at("product.commit.close_unrecorded"));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private boolean partialFlush(Snapshot snapshot) {
        List<String> worlds = snapshot.worlds();
        List<String[]> parsed = flushRows(snapshot);
        if (parsed.size() < worlds.size()) {
            return true;
        }
        for (String[] row : parsed) {
            if (!worlds.contains(row[0]) || stepsTaken(row[4]) < 4) {
                return true;
            }
        }
        return false;
    }

    private boolean write(String stage, Snapshot snapshot, String note, String rowStatus,
                          String rowReason, String rowExit) {
        boolean entryStage = !"C4".equals(stage);
        Path file = resolve();
        if (file == null) {
            noteEvidence(entryStage ? "C1_WRITE_FAILED" : "C4_WRITE_FAILED");
            return false;
        }
        String text = (rows == 0L ? headerLine() : "")
            + render(stage, snapshot, note, rowStatus, rowReason, rowExit);
        long size = text.getBytes(StandardCharsets.UTF_8).length;
        if (!fits(size, entryStage)) {
            capReached = true;
            noteEvidence("FILE_CAP_REACHED");
            console("status=FAILED reason=FILE_CAP_REACHED exit=evidence");
            return false;
        }
        boolean first = rows == 0L;
        String problem = attempt(file, text, first);
        if (!problem.isEmpty()) {
            noteEvidence(entryStage ? "C1_WRITE_FAILED" : "C4_WRITE_FAILED");
            console("status=FAILED reason=" + problem + " exit=evidence");
            return false;
        }
        writeSeq++;
        rows++;
        bytes += size;
        CloseSealFiles.Whole whole = CloseSealFiles.Whole.of(file);
        String refused = receipt(file, whole);
        console("stage=" + stage + " writeSeq=" + writeSeq + " md5=" + prefix(digestOf(whole))
            + " bytes=" + lengthOf(whole) + " path=" + file.toAbsolutePath() + " status=" + status
            + " reason=" + reason + " fallback=" + failureReason());
        receipted(stage, refused);
        return true;
    }

    /** Whether a record fits under both caps. A record that is not the terminal one has to leave
     * the room the terminal record needs: a cap that drops a record must not also drop the verdict
     * that says a record was dropped, so an entry record is admitted only with one row and one row
     * size plus a fixed allowance still to spare. */
    private boolean fits(long size, boolean entryStage) {
        if (entryStage) {
            return rows + 2L <= limits.maxRows()
                && bytes + 2L * size + TERMINAL_SEAT_BYTES <= limits.maxBytes();
        }
        return rows + 1L <= limits.maxRows() && bytes + size <= limits.maxBytes();
    }

    private Path resolve() {
        if (resolved != null) {
            return resolved;
        }
        if (resolutionFailed) {
            return null;
        }
        CloseSealFiles.Resolved candidate = CloseSealFiles.diagDir(diagDirValue);
        if (candidate.dir() != null) {
            Path runDir = candidate.dir().resolve(runId);
            String problem = CloseSealFiles.prepare(candidate.dir(), runDir);
            if (problem.isEmpty()) {
                Path stage = limits.tmpDir() == null ? candidate.dir() : limits.tmpDir();
                if (CloseSealFiles.sameFileStore(stage, runDir)) {
                    stagingDir = stage;
                    return resolved = runDir.resolve(CloseSealFiles.FILE_NAME);
                }
                problem = "cross-filesystem";
                primaryAtomic = "refused";
            }
            fallbackReason = problem;
        } else {
            fallbackReason = candidate.reason();
        }
        Path root = limits.fallbackRoot() == null ? Paths.get(CloseSealFiles.FALLBACK_ROOT)
            : limits.fallbackRoot();
        Path runDir = root.resolve(runId);
        return resolved = fallbackTo(runDir, root);
    }

    private Path fallbackTo(Path runDir, Path root) {
        String problem = CloseSealFiles.prepare(root, runDir);
        if (!problem.isEmpty()) {
            resolutionFailed = true;
            String primary = fallbackReason.isEmpty() ? "-" : fallbackReason;
            fallbackReason = "no-fallback-path";
            console("status=FAILED reason=no-fallback-path exit=evidence primary=" + primary
                + " fallback=" + problem);
            return null;
        }
        stagingDir = runDir;
        mode = "ATOMIC_MOVE";
        return runDir.resolve(CloseSealFiles.FILE_NAME);
    }

    private String attempt(Path file, String text, boolean first) {
        int tries = 0;
        while (true) {
            String problem = first ? CloseSealFiles.create(stagingDir, file, text)
                : CloseSealFiles.append(file, text);
            if (problem.isEmpty()) {
                return "";
            }
            tries++;
            retries = tries;
            if (tries > limits.maxRetries()) {
                return problem;
            }
            long wait = backoff(tries);
            if (!withinDeadline(wait)) {
                deadlineHit = true;
                return problem;
            }
            if (!sleep(wait)) {
                return problem;
            }
        }
    }

    private long backoff(int tries) {
        List<Long> table = limits.backoffMillis();
        if (table == null || table.isEmpty()) {
            return 0L;
        }
        return table.get(Math.min(tries, table.size()) - 1);
    }

    private boolean withinDeadline(long waitMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis) < deadlineNanos;
    }

    private boolean sleep(long waitMillis) {
        if (waitMillis <= 0L) {
            return true;
        }
        try {
            Thread.sleep(waitMillis);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The increment hash of one record, appended beside the record file. Both numbers on the line
     * come from one read of the file as it stands after that record became durable, so the digest
     * and the length describe the same object and either one recomputes from the file at that
     * stage. The reason it could not be written is returned rather than dropped: an increment that
     * never lands, and a digest that could not be taken, are both evidence failures and have to
     * reach the terminal classification and the console. */
    private String receipt(Path file, CloseSealFiles.Whole whole) {
        Path fileReceipt = file.getParent().resolve(CloseSealFiles.RECEIPT_NAME);
        String refused = CloseSealFiles.append(fileReceipt, digestOf(whole) + "  "
            + CloseSealFiles.FILE_NAME + "  bytes=" + lengthOf(whole) + "  writeSeq=" + writeSeq
            + LF);
        if (whole.read()) {
            return refused;
        }
        return refused.isEmpty() ? whole.problem() : refused + '+' + whole.problem();
    }

    /** The digest of the record file as it stands, or a dash where it could not be read: the
     * console line carries the same pair as the increment line, so neither path can be read as the
     * digest of something that is not the file. */
    private static String digestOf(CloseSealFiles.Whole whole) {
        return whole.read() ? md5(whole.bytes()) : "-";
    }

    private static String lengthOf(CloseSealFiles.Whole whole) {
        return whole.read() ? Long.toString(whole.bytes().length) : "-";
    }

    private static String prefix(String digest) {
        return digest.length() > 16 ? digest.substring(0, 16) : digest;
    }

    /** What a refused receipt does to the reading: it is counted, it is named on the console beside
     * the record line, and the terminal record books it as the evidence failure it is. The record
     * itself is already durable, so a receipt that cannot be written never takes a record with it
     * and is never dropped in silence. */
    private void receipted(String stage, String problem) {
        if (problem.isEmpty()) {
            return;
        }
        receiptFailures++;
        if (receiptError.isEmpty()) {
            receiptError = problem;
        }
        noteEvidence("C4_WRITE_FAILED");
        status = "FAILED";
        reason = "C4_WRITE_FAILED";
        exit = "evidence";
        console("status=FAILED reason=C4_WRITE_FAILED exit=evidence receipt=" + problem
            + " stage=" + stage + " writeSeq=" + writeSeq + " receiptFailures=" + receiptFailures);
    }

    private void console(String text) {
        lastConsole = "[PRTS] seal " + text;
        if (consoleLines.size() >= MAX_CONSOLE_LINES) {
            consoleLines.remove(0);
        }
        consoleLines.add(lastConsole);
        System.out.println(lastConsole);
    }

    private String render(String stage, Snapshot snapshot, String note, String rowStatus,
                          String rowReason, String rowExit) {
        List<String> cells = new ArrayList<>();
        cells.add(SCHEMA);
        cells.add(runId);
        cells.add(Integer.toString(writeSeq + 1));
        cells.add(Integer.toString(entries.get()));
        cells.add(stage);
        cells.add(SOURCE);
        cells.add(OWNER);
        cells.add(epochCell(snapshot));
        cells.add(cell(snapshot, G_IN_FLIGHT));
        cells.add(cell(snapshot, G_PENDING));
        cells.add(unrecordedCell(snapshot));
        cells.add(cell(snapshot, G_SEALED));
        cells.add(partialCell(snapshot));
        cells.add(errorCell());
        cells.add(cell(snapshot, G_FLUSH));
        cells.add(unloadCell(snapshot));
        cells.add(reclaimCell(snapshot));
        cells.add(cell(snapshot, G_ARENA));
        cells.add(cell(snapshot, G_RESERVATION));
        cells.add(wiringCell(snapshot));
        cells.add(cell(snapshot, G_OWNER));
        cells.add(worldSetCell(snapshot));
        cells.add(receiptCell(note, rowStatus, rowReason, rowExit));
        cells.add("C4".equals(stage) ? rowStatus : "-");
        String prefix = String.join(String.valueOf(TAB), cells);
        return prefix + TAB + md5(prefix).substring(0, 16) + LF;
    }

    private static String headerLine() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < COLUMNS.length - 1; index++) {
            if (index > 0) {
                builder.append(TAB);
            }
            builder.append(COLUMNS[index]);
        }
        String prefix = builder.toString();
        return prefix + TAB + md5(prefix).substring(0, 16) + LF;
    }

    private String cell(Snapshot snapshot, String[] keys) {
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            parts.add(key + '=' + snapshot.at(key));
        }
        return String.join("|", parts);
    }

    private String epochCell(Snapshot snapshot) {
        List<String> parts = new ArrayList<>();
        for (String key : G_EPOCH) {
            parts.add(key + '=' + snapshot.at(key));
        }
        for (String[] row : flushRows(snapshot)) {
            parts.add("world." + row[0] + '=' + row[1]);
        }
        return String.join("|", parts);
    }

    private String unrecordedCell(Snapshot snapshot) {
        boolean wired = !NOT_WIRED.equals(snapshot.at("product.commit.controller_wiring"));
        return "close_unrecorded=" + snapshot.at("product.commit.close_unrecorded")
            + "|scope=" + (wired ? "WIRED" : NOT_WIRED)
            + "|sealed_identities=" + snapshot.at("product.commit.chain_sealed_identities")
            + "|chain_dispositions=" + snapshot.at("product.commit.chain_dispositions");
    }

    private String wiringCell(Snapshot snapshot) {
        List<String> parts = new ArrayList<>();
        for (String key : G_WIRING) {
            parts.add(key + '=' + snapshot.at(key));
        }
        parts.add("closeOutcome=" + closeOutcome);
        parts.add("closeScope=" + (NOT_WIRED.equals(snapshot.at("product.commit.controller_wiring"))
            ? NOT_WIRED : "WIRED"));
        return String.join("|", parts);
    }

    private String partialCell(Snapshot snapshot) {
        List<String> parts = new ArrayList<>();
        List<String> worlds = snapshot.worlds();
        List<String[]> parsed = flushRows(snapshot);
        List<String> covered = new ArrayList<>();
        for (String[] row : parsed) {
            covered.add(row[0]);
            int taken = stepsTaken(row[4]);
            if (taken >= 4 && worlds.contains(row[0])) {
                continue;
            }
            parts.add("world=" + row[0] + "|stepsTaken=" + taken + "|steps=" + row[4]
                + "|inC1WorldSet=" + (worlds.contains(row[0]) ? 1 : 0));
        }
        for (String world : worlds) {
            if (!covered.contains(world)) {
                parts.add("world=" + world + "|stepsTaken=0|steps=-|inC1WorldSet=1");
            }
        }
        return parts.isEmpty() ? "-" : String.join(";", parts)
            + ";worldsExpected=" + worlds.size() + ";worldsWithRows=" + parsed.size();
    }

    /** The error cell, bounded by what the writer kept and naming what it did not. */
    private String errorCell() {
        if (errors.isEmpty()) {
            return errorsDropped == 0 ? "-" : "errorsDropped=" + errorsDropped;
        }
        String joined = String.join(";", errors);
        return errorsDropped == 0 ? joined : joined + ";errorsDropped=" + errorsDropped;
    }

    /** The unload cell of this record. The record is copied before any world is unloaded and the
     * delivery the stopping injection raises comes after it, so this cell can never carry a delivery:
     * a process that records deliveries declares itself frozen before them and names the other family
     * as the one that carries the delivered bits, and a process that records none keeps the default.
     * Nothing in this cell is written by a delivery. */
    private String unloadCell(Snapshot snapshot) {
        boolean recorded = UNLOAD_WIRED.equals(snapshot.at(UNLOAD_WIRING_KEY));
        List<String> parts = new ArrayList<>();
        for (String world : snapshot.worlds()) {
            parts.add(world + "=" + (recorded ? "PENDING_AFTER_RECORD" : "NEVER_RUN"));
        }
        return "unloadSource=" + (recorded ? "record-frozen-before-delivery" : "default-never-run")
            + "|" + (parts.isEmpty() ? "-" : String.join(",", parts));
    }

    private String reclaimCell(Snapshot snapshot) {
        List<String> parts = new ArrayList<>();
        for (String[] row : flushRows(snapshot)) {
            parts.add(row[0] + '=' + row[3]);
        }
        parts.add("rows=" + (flushRows(snapshot).isEmpty() ? "NOT_EXECUTABLE" : "present"));
        for (String key : G_RECLAIM) {
            parts.add(key + '=' + snapshot.at(key));
        }
        return String.join("|", parts);
    }

    private String worldSetCell(Snapshot snapshot) {
        String current = worldSet(snapshot.worlds());
        return current + "|sameAsC1=" + (current.equals(worldSetAtEntry) ? 1 : 0);
    }

    private String receiptCell(String note, String rowStatus, String rowReason,
                               String rowExit) {
        return "phase=" + currentPhase() + "|phases=" + phaseTrace() + "|pid="
            + ProcessHandle.current().pid() + "|startedAtMillis=" + STARTED_AT.toEpochMilli()
            + "|budgetMs=" + budget() + "|elapsedMs=" + Math.max(0L,
            (System.nanoTime() - startedNanos) / 1_000_000L)
            + "|deadline=" + (deadlineHit ? "EXCEEDED" : "WITHIN") + "|atomic=" + mode
            + "|primaryAtomic=" + primaryAtomic + "|fallback=" + failureReason() + "|retries="
            + retries + "|rows=" + rows + "|bytes=" + bytes + "|cap="
            + (capReached ? "REACHED" : "WITHIN") + "|file=" + CloseSealFiles.FILE_NAME
            + "|evidenceExit=" + evidenceExit + "|statusScale=" + String.join(",", STATUSES)
            + "|reasonScale=" + String.join(",", REASONS) + "|evidenceExitScale="
            + String.join(",", EVIDENCE_EXITS) + "|note=" + (note.isEmpty() ? "-" : note)
            + "|rowStatus=" + rowStatus + "|rowReason=" + rowReason + "|rowExit=" + rowExit
            + "|receiptFailures=" + receiptFailures + "|receiptError="
            + (receiptError.isEmpty() ? "-" : receiptError);
    }

    private static String worldSet(List<String> worlds) {
        return "count=" + worlds.size() + "|ids="
            + (worlds.isEmpty() ? "-" : String.join(",", worlds));
    }

    private static List<String[]> flushRows(Snapshot snapshot) {
        List<String[]> rows = new ArrayList<>();
        String value = snapshot.at("product.commit.stop_flush_rows");
        if (value.isEmpty() || "-".equals(value)) {
            return rows;
        }
        for (String row : value.split(",")) {
            String[] parts = row.split(":");
            if (parts.length < 5) {
                continue;
            }
            int tail = parts.length - 4;
            StringBuilder world = new StringBuilder();
            for (int index = 0; index < tail; index++) {
                if (index > 0) {
                    world.append(':');
                }
                world.append(parts[index]);
            }
            rows.add(new String[] {world.toString(), parts[tail], parts[tail + 1], parts[tail + 2],
                parts[tail + 3]});
        }
        return rows;
    }

    private static int stepsTaken(String steps) {
        int taken = 0;
        for (String part : steps.split(">")) {
            try {
                if (Long.parseLong(part.trim()) > 0L) {
                    taken++;
                }
            } catch (NumberFormatException error) {
                return 0;
            }
        }
        return taken;
    }

    private static String firstLine(String message) {
        String text = message == null ? "-" : message.split("\\R", 2)[0];
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    static String md5(String text) {
        return md5(text.getBytes(StandardCharsets.UTF_8));
    }

    static String md5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] sum = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(sum.length * 2);
            for (byte value : sum) {
                builder.append(Character.forDigit((value >> 4) & 0xf, 16));
                builder.append(Character.forDigit(value & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            return "0".repeat(32);
        }
    }
}
