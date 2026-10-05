/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Watches the four faces a tick can go wrong on - the write right, the version slot, the wait
 * bound and the ladder order - and reports what it saw. Every violation is counted twice, once per
 * site and once per world, because a count that only knows the kind cannot answer which site to look
 * at and a count that only knows the site cannot answer which world paid for it.
 *
 * <p>The net reports; it repairs nothing. A side effect that already left the kernel stays where it
 * is, and the net only makes it countable. The degradation switch it carries decides whether a
 * violation becomes an escalation candidate, and even switched on it changes no schedule: the
 * escalation is a count another layer could read, not a call the net makes.
 *
 * <p>Counts are cumulative over the life of the instance; the per-tick view is the difference
 * between two reads. {@link #review} is called once per tick by the driver and closes the cascade
 * window of the tick it ends. */
public final class SafetyNet {

    /** The world of a counter that is not world scoped. It is not a world id and is never resolved
     * as one. */
    public static final String NO_WORLD = "*";

    /** The world a counter that is not world scoped is filed under. */
    public static final String NO_WORLD_SITE = NO_WORLD;

    /** The five kinds of violation the net counts. The set is closed, and every kind answers with a
     * refusal code that already exists, so reporting adds no code to the closed set. */
    public enum ViolationKind {

        /** A writer that is not the owner of the target, or a write set that disagrees with the job. */
        CROSS_OWNER_WRITE("cross-owner-write", "owner", RejectCode.WRITE_DENIED_NOT_OWNER),

        /** Two writers on one slot, or a slot holding a version other than the expected one. */
        VERSION_CONFLICT("version-conflict", "version", RejectCode.VERSION_MISMATCH),

        /** A path no site declared, so nothing could say what it waits for. */
        UNKNOWN_ACCESS("unknown-access", "declaration", RejectCode.NATIVE_UNDECLARED),

        /** A wait that passed its bound: the bounded-wait contract failed for that path. */
        HARD_TIMEOUT("hard-timeout", "wait", RejectCode.WAIT_BOUND_EXCEEDED),

        /** A rung reached past the next one, which skips the order the rungs are walked in. */
        ESCALATE_SERIAL("escalate-serial", "serial", RejectCode.SCALE_BUDGET_EXCEEDED);

        private final String key;
        private final String detector;
        private final RejectCode code;

        ViolationKind(String key, String detector, RejectCode code) {
            this.key = key;
            this.detector = detector;
            this.code = code;
        }

        public String key() {
            return key;
        }

        /** The detector that watches this kind: the write right probe, the conflict probe, the
         * timeout review or the escalation review. */
        public String detector() {
            return detector;
        }

        public RejectCode code() {
            return code;
        }

        public static ViolationKind of(String key) {
            for (ViolationKind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }

        public static int kindCount() {
            return values().length;
        }
    }

    /** One violation as the net records it. The world and the site are both carried so a kind can be
     * counted on either dimension; a counter that has no world says so with {@link #NO_WORLD}. */
    public record ViolationReport(ViolationKind kind, String worldId, String siteId, long tickIndex,
                                  RejectCode code, String evidence, String disposition,
                                  int cascade) {

        public ViolationReport {
            worldId = worldId == null || worldId.isEmpty() ? NO_WORLD : worldId;
            siteId = siteId == null ? "" : siteId;
            evidence = evidence == null ? "" : evidence;
            disposition = disposition == null ? "" : disposition;
        }

        public boolean evidencePresent() {
            return !evidence.isEmpty();
        }

        public String key() {
            return kind.key() + "@" + worldId + "/" + siteId;
        }
    }

    /** Which world and which site a counter belongs to. A counter that is not world scoped carries
     * {@link #NO_WORLD} and says so rather than borrowing a world id. */
    public record Point(String worldId, String siteId) {

        public Point {
            worldId = worldId == null || worldId.isEmpty() ? NO_WORLD : worldId;
            siteId = siteId == null ? "" : siteId;
        }

        public String key() {
            return worldId + "/" + siteId;
        }
    }

    /** The raw counters one review reads. Every map holds the cumulative value of one layer's own
     * counter; the net keeps no second copy of them, so a violation can always be traced back to the
     * layer that saw it. */
    public record TickSources(Map<Point, Long> writeDenied, Map<Point, Long> commitViolations,
                              Map<Point, Long> waitOverruns, long versionMismatch,
                              long unregisteredAccess, long rungEntered, long rungSkipped) {

        public TickSources {
            writeDenied = copy(writeDenied);
            commitViolations = copy(commitViolations);
            waitOverruns = copy(waitOverruns);
        }

        public static TickSources empty() {
            return new TickSources(Map.of(), Map.of(), Map.of(), 0L, 0L, 0L, 0L);
        }

        private static Map<Point, Long> copy(Map<Point, Long> source) {
            return source == null ? Map.of() : Map.copyOf(source);
        }
    }

    /** One kind and how often it was seen and how often it became an escalation candidate. */
    public record KindCounts(String kind, long total, long escalated) {
    }

    /** One cell of a dimension: the site or the world, and the count on it. */
    public record Cell(String key, long count) {
    }

    /** How far the violations of the newest closed window cascaded and where the cap stopped them. */
    public record Cascade(long cap, long depth, long steps, long capped, long stopped) {
    }

    private final BooleanSupplier degradeSwitch;
    private final IntSupplier cascadeCap;
    private final Map<String, LongAdder> byKind = new LinkedHashMap<>();
    private final Map<String, LongAdder> byKindSite = new LinkedHashMap<>();
    private final Map<String, LongAdder> byKindWorld = new LinkedHashMap<>();
    private final Map<String, LongAdder> escalatedByKind = new LinkedHashMap<>();
    private final Map<String, Integer> window = new LinkedHashMap<>();
    private final LongAdder evidenceEmpty = new LongAdder();
    private final LongAdder escalated = new LongAdder();
    private final LongAdder cascadeSteps = new LongAdder();
    private final LongAdder cascadeCapped = new LongAdder();
    private final LongAdder cascadeStopped = new LongAdder();
    private int windowDepth;
    private long lastWindowDepth;
    private TickSources previous = TickSources.empty();

    public SafetyNet(BooleanSupplier degradeSwitch, IntSupplier cascadeCap) {
        this.degradeSwitch = degradeSwitch == null ? () -> false : degradeSwitch;
        this.cascadeCap = cascadeCap == null ? () -> 3 : cascadeCap;
    }

    /** Reads one tick of the layers around the net and reports what the difference shows. The call
     * closes the cascade window of the tick it ends. */
    public synchronized List<ViolationReport> review(TickSources sources, long tickIndex) {
        TickSources now = sources == null ? TickSources.empty() : sources;
        List<ViolationReport> reports = new ArrayList<>();
        diff(reports, ViolationKind.CROSS_OWNER_WRITE, now.writeDenied(), previous.writeDenied(),
            tickIndex, "denied write");
        diff(reports, ViolationKind.VERSION_CONFLICT, now.commitViolations(),
            previous.commitViolations(), tickIndex, "commit out of plan order");
        long mismatches = now.versionMismatch() - previous.versionMismatch();
        if (mismatches > 0L) {
            reports.add(record(ViolationKind.VERSION_CONFLICT, NO_WORLD,
                "ledger:version", tickIndex, mismatches + " version mismatch(es)",
                "counted; the refused write stays refused", mismatches));
        }
        diff(reports, ViolationKind.HARD_TIMEOUT, now.waitOverruns(), previous.waitOverruns(),
            tickIndex, "wait past its bound");
        long unregistered = now.unregisteredAccess() - previous.unregisteredAccess();
        if (unregistered > 0L) {
            reports.add(record(ViolationKind.UNKNOWN_ACCESS, NO_WORLD,
                "wait:unregistered", tickIndex, unregistered + " undeclared access(es)",
                "counted; the site stays unregistered until it declares", unregistered));
        }
        long skipped = now.rungSkipped() - previous.rungSkipped();
        if (skipped > 0L) {
            reports.add(record(ViolationKind.ESCALATE_SERIAL, NO_WORLD,
                "ladder:order", tickIndex, skipped + " rung(s) skipped",
                "counted; the ladder walked the rungs it passed", skipped));
        }
        previous = now;
        closeWindow();
        return reports;
    }

    /** Records one violation seen at the moment it happened. A report without evidence is refused:
     * the count would otherwise name an event nobody can go back to. */
    public synchronized ViolationReport report(ViolationKind kind, String worldId, String siteId,
                                               long tickIndex, String evidence, String disposition) {
        return record(kind, worldId, siteId, tickIndex, evidence, disposition, 1L);
    }

    /** Records one batch of violations that came from one counter. The batch counts as the number
     * of violations it stands for and as one step of the cascade. */
    private synchronized ViolationReport record(ViolationKind kind, String worldId, String siteId,
                                                long tickIndex, String evidence, String disposition,
                                                long count) {
        ViolationReport report = new ViolationReport(kind, worldId, siteId, tickIndex,
            kind == null ? null : kind.code(), evidence, disposition, 0);
        if (kind == null || !report.evidencePresent()) {
            evidenceEmpty.increment();
            return report;
        }
        byKind.computeIfAbsent(kind.key(), key -> new LongAdder()).add(count);
        byKindSite.computeIfAbsent(kind.key() + "|" + report.siteId(), key -> new LongAdder())
            .add(count);
        byKindWorld.computeIfAbsent(kind.key() + "|" + report.worldId(), key -> new LongAdder())
            .add(count);
        int seen = window.merge(kind.key() + "@" + report.worldId(),
            (int) Math.min(Integer.MAX_VALUE, count), Integer::sum);
        windowDepth = Math.max(windowDepth, seen);
        int cap = Math.max(1, cascadeCap.getAsInt());
        boolean escalation = degradeSwitch.getAsBoolean();
        if (seen > 1) {
            cascadeSteps.increment();
        }
        if (seen >= cap) {
            if (seen == cap) {
                cascadeCapped.increment();
            }
            cascadeStopped.increment();
            escalation = false;
        }
        if (escalation) {
            escalated.increment();
            escalatedByKind.computeIfAbsent(kind.key(), key -> new LongAdder()).increment();
        }
        return new ViolationReport(kind, report.worldId(), report.siteId(), tickIndex, report.code(),
            report.evidence(), report.disposition(), seen);
    }

    private void closeWindow() {
        lastWindowDepth = windowDepth;
        windowDepth = 0;
        window.clear();
    }

    private void diff(List<ViolationReport> reports, ViolationKind kind, Map<Point, Long> now,
                      Map<Point, Long> before, long tickIndex, String disposition) {
        for (Map.Entry<Point, Long> entry : now.entrySet()) {
            long delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
            if (delta <= 0L) {
                continue;
            }
            reports.add(record(kind, entry.getKey().worldId(), entry.getKey().siteId(), tickIndex,
                delta + " " + disposition + "(s) at " + entry.getKey().siteId(), disposition, delta));
        }
    }

    public synchronized long total() {
        long sum = 0L;
        for (LongAdder counter : byKind.values()) {
            sum += counter.sum();
        }
        return sum;
    }

    public synchronized long count(ViolationKind kind) {
        LongAdder counter = kind == null ? null : byKind.get(kind.key());
        return counter == null ? 0L : counter.sum();
    }

    public synchronized long countAt(ViolationKind kind, String siteId) {
        LongAdder counter = kind == null ? null : byKindSite.get(kind.key() + "|" + siteId);
        return counter == null ? 0L : counter.sum();
    }

    public synchronized long countIn(ViolationKind kind, String worldId) {
        LongAdder counter = kind == null ? null
            : byKindWorld.get(kind.key() + "|" + (worldId == null ? "" : worldId));
        return counter == null ? 0L : counter.sum();
    }

    /** The count of every kind, zero values included, so a closed set of five is always readable. */
    public synchronized List<KindCounts> kinds() {
        List<KindCounts> rows = new ArrayList<>(ViolationKind.kindCount());
        for (ViolationKind kind : ViolationKind.values()) {
            LongAdder escalatedOfKind = escalatedByKind.get(kind.key());
            rows.add(new KindCounts(kind.key(), count(kind),
                escalatedOfKind == null ? 0L : escalatedOfKind.sum()));
        }
        return rows;
    }

    public synchronized List<Cell> sites(ViolationKind kind) {
        return cells(byKindSite, kind == null ? null : kind.key() + "|");
    }

    public synchronized List<Cell> worlds(ViolationKind kind) {
        return cells(byKindWorld, kind == null ? null : kind.key() + "|");
    }

    private List<Cell> cells(Map<String, LongAdder> map, String prefix) {
        List<Cell> cells = new ArrayList<>();
        if (prefix == null) {
            return cells;
        }
        for (Map.Entry<String, LongAdder> entry : map.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                cells.add(new Cell(entry.getKey().substring(prefix.length()),
                    entry.getValue().sum()));
            }
        }
        return cells;
    }

    /** The cascade of the newest window, the one still open included: a reader that looks after a
     * report and before the next review sees the depth that report reached. */
    public synchronized Cascade cascade() {
        return new Cascade(Math.max(1, cascadeCap.getAsInt()), Math.max(lastWindowDepth, windowDepth),
            cascadeSteps.sum(), cascadeCapped.sum(), cascadeStopped.sum());
    }

    public synchronized boolean switchEnabled() {
        return degradeSwitch.getAsBoolean();
    }

    public synchronized long escalated() {
        return escalated.sum();
    }

    public synchronized long evidenceEmpty() {
        return evidenceEmpty.sum();
    }

    public synchronized void reset() {
        byKind.clear();
        byKindSite.clear();
        byKindWorld.clear();
        escalatedByKind.clear();
        window.clear();
        evidenceEmpty.reset();
        escalated.reset();
        cascadeSteps.reset();
        cascadeCapped.reset();
        cascadeStopped.reset();
        windowDepth = 0;
        lastWindowDepth = 0L;
        previous = TickSources.empty();
    }
}
