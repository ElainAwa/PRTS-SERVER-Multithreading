/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.SiteRegisterResult;

/** A wait point is complete when it names its producer, its progress signal, its timeout action
 * and where it degrades to; a row missing any of them is refused, not stored. */
public final class WaitPointRegistry {

    /** Marker of a call site that no wait point covers. */
    public static final String UNREGISTERED = "UNREGISTERED";

    /** The nine wait point rows the dependency contract writes down, in the order it writes them. A
     * row added later is appended: the keys of these nine are never renamed or renumbered. The
     * contract also gives each row a short code; that form is internal to the review materials and is
     * deliberately not carried into the published tree, where a code would be a leaked identifier. */
    public static final List<String> CONTRACT_ROWS = List.of("chunk", "xdomain", "xworld", "native",
        "net", "save", "region", "worldlife", "gen");

    private static final String CHUNK_PRODUCER = "chunk generation, light pipeline and world loader";
    private static final String CHUNK_PROGRESS = "progress.chunk.materialized_per_tick";

    // key | class | producer | signal form | progress reading | timeout action | degrade target |
    // world scope | call site
    private static final String[][] BUILTINS = {
        {"chunk", "chunk materialization", CHUNK_PRODUCER, "COUNT", CHUNK_PROGRESS,
            SiteInventory.FORCED_MATERIALIZATION, "read-only snapshot or placeholder upgrade",
            "per world", "chunk.materialize"},
        {"xdomain", "cross-domain write", "target domain owner", "WATERMARK",
            "intent.queue_depth", "hand the write to the intent channel and degrade the domain",
            "postpone to the next tick in order", "per world", "xdomain.intent"},
        {"xworld", "cross-world migration", "target world loader", "WATERMARK",
            "progress.xworld.migration_queue", "cancel the migration and keep the source state",
            "refuse and count on both dimensions", "per world", "xworld.migrate"},
        {"native", "native payload result", "native payload producer", "HEARTBEAT",
            "progress.native.steps", "terminate the call", "equivalent java implementation",
            "per payload", "native.result"},
        {"net", "network flush", "network send pipeline", "WATERMARK",
            "progress.net.send_queue_depth", "defer per player shard",
            "delay the bounded commit segment by one tick in order", "per world", "net.flush"},
        {"save", "storage flush", "region file writer", "COUNT", "progress.save.flushed_regions",
            "account for it and compensate on the next tick",
            "retry after the consistency point without skipping a segment", "per world",
            "save.flush"},
        {"region", "region split or merge", "region identity manager", "WATERMARK",
            "progress.region.stability_ticks_remaining", "keep the current split for one more window",
            "conservative split, neither split nor merge", "per region", "region.identity"},
        {"worldlife", "world load or unload", "world lifecycle owner", "HEARTBEAT",
            "write.world_epochs_changes", "refuse that creation or unload",
            "return to the previous stable world set", "per world", "worldlife.transition"},
        {"gen", "generation and light pipeline", "generation and light pipeline stage", "COUNT",
            "progress.gen.completed_segments", "lower the segment parallelism",
            "process segments serially", "per world", "gen.pipeline"}
    };

    private final Map<String, WaitPointEntry> rows = new LinkedHashMap<>();
    private final SiteInventory sites = new SiteInventory();
    private final Map<String, String> callSites = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> observedMax = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> overrunsByRow = new ConcurrentHashMap<>();
    private final Map<String, Integer> walkthrough = new ConcurrentHashMap<>();
    private final Map<String, Integer> unregisteredCallSites = new ConcurrentHashMap<>();
    private final WaitProgress progress = new WaitProgress();
    private final ForcedConvergence convergence = new ForcedConvergence();
    private final LongAdder waitOverruns = new LongAdder();
    private final LongAdder observations = new LongAdder();
    private final LongAdder unregisteredRefusals = new LongAdder();
    private final IntSupplier boundMs;
    private final BooleanSupplier refuseUnregistered;
    private volatile boolean overrunThisTick;

    /** Creates a registry that counts a wait at an unregistered call site and lets it run. */
    public WaitPointRegistry(IntSupplier boundMs) {
        this(boundMs, () -> false);
    }

    /** Creates a registry and registers the nine existing wait point classes. The refusal switch
     * answers whether a wait no row covers is only counted or also answered with a refusal code. */
    public WaitPointRegistry(IntSupplier boundMs, BooleanSupplier refuseUnregistered) {
        this.boundMs = boundMs;
        this.refuseUnregistered = refuseUnregistered;
        registerBuiltins();
    }

    /** Registers one wait point. */
    public synchronized RegisterResult registerWaitPoint(WaitPointDeclaration declaration) {
        if (declaration == null || declaration.wpId() == null || declaration.wpId().isBlank()) {
            return new RegisterResult.MissingElement(
                declaration == null ? "" : String.valueOf(declaration.wpId()), "identity");
        }
        if (rows.containsKey(declaration.wpId())) {
            return new RegisterResult.DuplicateWpId(declaration.wpId());
        }
        String missing = Dec19Elements.missingElement(declaration);
        if (missing != null) {
            return new RegisterResult.MissingElement(declaration.wpId(), missing);
        }
        WaitPointEntry entry = new WaitPointEntry(declaration.wpId(), declaration.className(),
            declaration.producer(), declaration.signal(), declaration.timeoutAction(),
            declaration.degradeTo(), declaration.worldScope(), declaration.callSiteRef(),
            declaration.revision());
        rows.put(entry.wpId(), entry);
        progress.declare(entry.wpId(), entry.signal());
        if (entry.callSiteRef() != null && !entry.callSiteRef().isBlank()) {
            callSites.put(entry.callSiteRef(), entry.wpId());
        }
        return new RegisterResult.Ok(entry);
    }

    /** Registers one call site. */
    public SiteRegisterResult registerSite(WaitSite site) {
        return sites.register(site);
    }

    public SiteInventory sites() {
        return sites;
    }

    /** Looks up a registered row. */
    public synchronized WaitPointEntry lookup(String wpId) {
        return wpId == null ? null : rows.get(wpId);
    }

    /** Looks up the row that covers a call site. */
    public WaitPointEntry lookupByCallSite(String callSiteRef) {
        String wpId = callSiteRef == null ? null : callSites.get(callSiteRef);
        return wpId == null ? null : lookup(wpId);
    }

    /** Every registered row, in registration order. */
    public synchronized List<WaitPointEntry> rows() {
        return List.copyOf(rows.values());
    }

    /** Whether the registry still serves exactly the nine rows the contract writes down, in that
     * order: unserved names a contract row no registered row answers, appended names a row that was
     * added later. A row appended later is the only legal difference, and it is reported, not hidden. */
    public synchronized NineRows nineRows() {
        List<String> unserved = new ArrayList<>();
        for (String row : CONTRACT_ROWS) {
            if (!rows.containsKey(row)) {
                unserved.add(row);
            }
        }
        List<String> appended = new ArrayList<>();
        for (String key : rows.keySet()) {
            if (!CONTRACT_ROWS.contains(key)) {
                appended.add(key);
            }
        }
        boolean aligned = unserved.isEmpty() && appended.isEmpty();
        return new NineRows(rows.size(), CONTRACT_ROWS.size(), aligned, List.copyOf(unserved),
            List.copyOf(appended));
    }

    /** Observes one wait. The answer carries the row's four contract items; a wait at a call site no
     * row covers carries none of them and, when the refusal switch is on, the refusal code. The
     * refusal is an answer, not an interception: the call site runs the host path either way. */
    public WaitObservation observeWait(String wpId, WaitSpan span) {
        if (span == null) {
            throw new IllegalArgumentException("an observation needs a wait span");
        }
        WaitPointEntry entry = lookup(wpId);
        String key = entry == null ? UNREGISTERED : entry.wpId();
        String rejection = null;
        if (entry == null) {
            noteUnregisteredWait(span);
            if (refuseUnregistered.getAsBoolean()) {
                unregisteredRefusals.increment();
                rejection = RejectTrigger.UNREGISTERED_WAIT_POINT.code().text();
            }
        } else if (span.progressReading() != null && !span.progressReading().isBlank()) {
            progress.noteObserved(entry.wpId(), parseReading(span.progressReading()));
        }
        sites.noteObserved(span.siteId());
        long waitMs = Math.max(0L, span.waitMs());
        AtomicLong max = observedMax.computeIfAbsent(key, ignored -> new AtomicLong());
        long current = max.accumulateAndGet(waitMs, Math::max);
        observations.increment();
        SelfTimers.noteWait(span.worldId(), span.siteId(), wpId, waitMs * 1_000_000L);
        boolean overrun = waitMs > boundMs.getAsInt();
        if (overrun) {
            waitOverruns.increment();
            overrunThisTick = true;
            overrunsByRow.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
        boolean wouldConverge = overrun && entry != null
            && SiteInventory.FORCED_MATERIALIZATION.equals(entry.timeoutAction());
        if (wouldConverge) {
            convergence.noteReached(entry.wpId(), entry.timeoutAction(), entry.degradeTo());
        }
        return new WaitObservation(current, overrun, wouldConverge, entry, rejection);
    }

    /** Counts a wait at a call site no row covers and puts it on the todo list. */
    public void noteUnregisteredWait(WaitSpan span) {
        String key = span.callSiteRef() == null || span.callSiteRef().isBlank()
            ? "unknown" : span.callSiteRef();
        unregisteredCallSites.merge(key, 1, Integer::sum);
    }

    /** Records that a fixture walked one wait point through an injection. */
    public void noteInjectionWalkthrough(String wpId) {
        if (wpId != null) {
            walkthrough.merge(wpId, 1, Integer::sum);
        }
    }

    /** Closes one host tick on the rollback gate. Nothing is rolled back here: the gate only records
     * whether the tick carried a wait that crossed the bound, and answers how long the clean run is. */
    public void noteTick() {
        convergence.noteTick(overrunThisTick);
        overrunThisTick = false;
    }

    /** Publishes the coverage self-check. */
    public synchronized CoverageReport reportCoverage() {
        int complete = 0;
        Map<String, Integer> walked = new LinkedHashMap<>();
        List<String> pendingElements = new ArrayList<>();
        for (WaitPointEntry entry : rows.values()) {
            if (entry.complete()) {
                complete++;
            } else {
                pendingElements.add(entry.wpId());
            }
            walked.put(entry.wpId(), walkthrough.getOrDefault(entry.wpId(), 0));
        }
        double coverage = rows.isEmpty() ? 100.0 : 100.0 * complete / rows.size();
        List<WaitSite> listed = sites.sites();
        List<String> uncovered = new ArrayList<>();
        int siteComplete = 0;
        for (WaitSite site : listed) {
            if (site.complete()) {
                siteComplete++;
            }
            if (!rows.containsKey(site.wpId())) {
                uncovered.add(site.siteId());
            }
        }
        double siteCoverage = listed.isEmpty() ? 100.0 : 100.0 * siteComplete / listed.size();
        return new CoverageReport(rows.size(), unregisteredCallSites.size(), coverage, walked,
            forcedConvergence(), listed.size(), pendingElements, siteComplete,
            sites.observedWithoutRow().size(), siteCoverage, sites.pendingElements(), uncovered);
    }

    /** How many forced convergence actions ran. This build implements no action, so the count is
     * published and stays at zero; the number of waits that reached the action is a separate reading,
     * so a candidate can never be read as something that executed. */
    public long forcedConvergence() {
        return convergence.effective();
    }

    public synchronized int registeredTotal() {
        return rows.size();
    }

    public int unregisteredCallSites() {
        return unregisteredCallSites.size();
    }

    public long maxWaitMs() {
        long max = 0L;
        for (AtomicLong value : observedMax.values()) {
            max = Math.max(max, value.get());
        }
        return max;
    }

    public long waitOverrunCount() {
        return waitOverruns.sum();
    }

    /** How many waits one row saw over the bound. */
    public long overrunOf(String wpId) {
        LongAdder count = wpId == null ? null : overrunsByRow.get(wpId);
        return count == null ? 0L : count.sum();
    }

    public long observationCount() {
        return observations.sum();
    }

    /** How many waits were answered with a refusal code; zero while the refusal switch is off. */
    public long refusedUnregistered() {
        return unregisteredRefusals.sum();
    }

    /** The upper bound one wait is measured against. */
    public int boundMs() {
        return boundMs.getAsInt();
    }

    public WaitProgress progress() {
        return progress;
    }

    public ForcedConvergence convergence() {
        return convergence;
    }

    public Map<String, Long> progressReadings() {
        return progress.values();
    }

    public Map<String, Integer> unregisteredTodo() {
        return Map.copyOf(unregisteredCallSites);
    }

    private void registerBuiltins() {
        for (String[] builtin : BUILTINS) {
            WaitPointEntry entry = new WaitPointEntry(builtin[0], builtin[1], builtin[2],
                new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.valueOf(builtin[3]),
                    builtin[4]), builtin[5], builtin[6], builtin[7], builtin[8], 1);
            rows.put(entry.wpId(), entry);
            callSites.put(entry.callSiteRef(), entry.wpId());
            progress.declare(entry.wpId(), entry.signal());
        }
    }

    /** Clears the counters a fixture owns; the rows, the sites and the signal bindings stay. */
    public void resetReadings() {
        waitOverruns.reset();
        observations.reset();
        unregisteredRefusals.reset();
        observedMax.clear();
        overrunsByRow.clear();
        unregisteredCallSites.clear();
        walkthrough.clear();
        progress.reset();
        convergence.reset();
        overrunThisTick = false;
    }

    private static long parseReading(String reading) {
        try {
            return Long.parseLong(reading.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /** One registered wait point. A registered row is immutable: a new revision is a new registration,
     * never an edit in place, so a reader of the coverage report sees exactly which declaration
     * produced a row. */
    public record WaitPointEntry(String wpId, String className, String producer,
                                 Dec19Elements.ProgressSignal signal, String timeoutAction,
                                 String degradeTo, String worldScope, String callSiteRef,
                                 int revision) {

        public boolean complete() {
            return Dec19Elements.missingElement(new WaitPointDeclaration(wpId, className, producer,
                signal, timeoutAction, degradeTo, worldScope, callSiteRef, revision)) == null;
        }
    }

    /** A wait point offered for registration. The four elements - producer, progress signal, timeout
     * action and degradation target - are mandatory; the rest identifies the row and the call site it
     * covers. */
    public record WaitPointDeclaration(String wpId, String className, String producer,
                                       Dec19Elements.ProgressSignal signal, String timeoutAction,
                                       String degradeTo, String worldScope, String callSiteRef,
                                       int revision) {
    }

    /** What the registry answers about one wait. The answer carries the row's four contract items, so
     * one observation says who produces the thing being waited for, which progress signal the row
     * publishes, what its bound would trigger and where it degrades to; the rejection is the code a
     * refused wait carries, and is null when the wait was not refused. */
    public record WaitObservation(long maxWaitMs, boolean overrun, boolean wouldConverge,
                                  WaitPointEntry row, String rejection) {

        public boolean refused() {
            return rejection != null;
        }

        public String wpId() {
            return row == null ? null : row.wpId();
        }

        public String producer() {
            return row == null ? null : row.producer();
        }

        public Dec19Elements.ProgressSignal signal() {
            return row == null ? null : row.signal();
        }

        public String timeoutAction() {
            return row == null ? null : row.timeoutAction();
        }

        public String degradeTo() {
            return row == null ? null : row.degradeTo();
        }

        public boolean complete() {
            return row != null && row.complete();
        }
    }

    /** How the registry lines up with the nine rows the contract writes down. Rows counts what is
     * registered, contractRows is the contract's own count, unserved names a contract row no row
     * answers and appended names a row added later; aligned is true only when all nine are served and
     * nothing was appended. */
    public record NineRows(int rows, int contractRows, boolean aligned, List<String> unserved,
                           List<String> appended) {
    }

    /** One observed wait. The wait point may be unknown: an observation is recorded either way,
     * because a wait at a call site no row covers is exactly what the coverage report has to show. */
    public record WaitSpan(String wpId, String callSiteRef, String siteId, String worldId,
                           long tickIndex, long waitMs, String progressReading) {
    }

    /** The result of offering a wait point for registration. Three outcomes, and no silent fourth: the
     * row was stored, the identity is already taken, or one of the four elements is missing and is
     * named. */
    public sealed interface RegisterResult {

        /** The row was stored. */
        record Ok(WaitPointEntry entry) implements RegisterResult {
        }

        /** The identity is already registered; rows are never replaced silently. */
        record DuplicateWpId(String wpId) implements RegisterResult {
        }

        /** One of the four elements is missing. The refusal the store answers with is the one the
         * contract names for an incomplete declaration. */
        record MissingElement(String wpId, String which) implements RegisterResult {

            public String code() {
                return RejectTrigger.WAIT_POINT_ELEMENT_MISSING.code().text();
            }
        }
    }
}
