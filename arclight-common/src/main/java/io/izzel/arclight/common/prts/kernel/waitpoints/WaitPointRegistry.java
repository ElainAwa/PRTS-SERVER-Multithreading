/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.SiteRegisterResult;

/** A wait point is complete when it names its producer, its progress signal, its timeout action
 * and where it degrades to; a row missing any of them is refused, not stored. */
public final class WaitPointRegistry {

    /** Marker of a call site that no wait point covers. */
    public static final String UNREGISTERED = "UNREGISTERED";

    private static final String[][] BUILTINS = {
        {"chunk", "chunk materialization", "chunk source", "COUNT",
            "progress.chunk.queue_depth", "return a snapshot and register the need",
            "read-only snapshot", "per world", "chunk.materialize"},
        {"xdomain", "cross-domain write", "domain job", "COUNT",
            "progress.xdomain.queue_depth", "postpone to the next tick in order",
            "intent queue", "per world", "xdomain.intent"},
        {"xworld", "cross-world migration", "migration job", "WATERMARK",
            "progress.xworld.watermark", "postpone the migration",
            "source world unchanged", "per world", "xworld.migrate"},
        {"native", "native payload result", "native payload", "HEARTBEAT",
            "progress.native.heartbeat", "answer a placeholder and upgrade when ready",
            "equivalent java path", "per payload", "native.result"},
        {"net", "network flush", "network stage", "COUNT",
            "progress.net.pending", "defer to the next tick",
            "queued send", "per world", "net.flush"},
        {"save", "storage flush", "storage stage", "COUNT",
            "progress.save.dirty", "defer to the next cycle",
            "bounded write set", "per world", "save.flush"},
        {"region", "region split or merge", "region identity manager", "WATERMARK",
            "progress.region.stability_ticks", "keep the previous identity",
            "stable snapshot", "per region", "region.identity"},
        {"worldlife", "world load or unload", "lifecycle service", "HEARTBEAT",
            "progress.worldlife.heartbeat", "keep the world set as it is",
            "previous world set", "per world", "worldlife.transition"},
        {"gen", "generation and light pipeline", "generation stage", "COUNT",
            "progress.gen.queue_depth", "defer to the next tick",
            "generation contract", "per world", "gen.pipeline"}
    };

    private final Map<String, WaitPointEntry> rows = new LinkedHashMap<>();
    private final SiteInventory sites = new SiteInventory();
    private final Map<String, String> callSites = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> observedMax = new ConcurrentHashMap<>();
    private final Map<String, Long> progress = new ConcurrentHashMap<>();
    private final Map<String, Integer> walkthrough = new ConcurrentHashMap<>();
    private final Map<String, Integer> unregisteredCallSites = new ConcurrentHashMap<>();
    private final LongAdder waitOverruns = new LongAdder();
    private final LongAdder observations = new LongAdder();
    private final IntSupplier boundMs;

    /** Creates a registry and registers the nine existing wait point classes. */
    public WaitPointRegistry(IntSupplier boundMs) {
        this.boundMs = boundMs;
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

    /** Observes one wait. */
    public WaitObservation observeWait(String wpId, WaitSpan span) {
        if (span == null) {
            throw new IllegalArgumentException("an observation needs a wait span");
        }
        WaitPointEntry entry = lookup(wpId);
        String key = entry == null ? UNREGISTERED : entry.wpId();
        if (entry == null) {
            noteUnregisteredWait(span);
        } else if (span.progressReading() != null && !span.progressReading().isBlank()) {
            progress.put(entry.wpId(), parseReading(span.progressReading()));
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
        }
        return new WaitObservation(current, overrun, span.progressReading());
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

    public long forcedConvergence() {
        return 0L;
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

    public long observationCount() {
        return observations.sum();
    }

    public synchronized Map<String, Long> progressReadings() {
        Map<String, Long> readings = new LinkedHashMap<>();
        for (String wpId : rows.keySet()) {
            readings.put(wpId, progress.getOrDefault(wpId, 0L));
        }
        return readings;
    }

    public Map<String, Integer> unregisteredTodo() {
        return Map.copyOf(unregisteredCallSites);
    }

    private void registerBuiltins() {
        for (String[] builtin : BUILTINS) {
            registerWaitPoint(new WaitPointDeclaration(builtin[0], builtin[1], builtin[2],
                new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.valueOf(builtin[3]),
                    builtin[4]), builtin[5], builtin[6], builtin[7], builtin[8], 1));
        }
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

    /** What the registry answers about one wait. The answer is a reading, not a command: it reports
     * the longest wait seen for the row, whether this wait crossed the configured upper bound and the
     * progress value that came with it. */
    public record WaitObservation(long maxWaitMs, boolean overrun, String progressReading) {
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

        /** One of the four elements is missing. */
        record MissingElement(String wpId, String which) implements RegisterResult {
        }
    }
}
