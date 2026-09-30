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

/**
 * The single legal list of wait points, and the coverage it can prove about itself.
 *
 * <p>A wait point is complete when it names its producer, its progress signal, its timeout action
 * and where it degrades to; a row missing any of them is refused, not stored. The registry starts
 * with the nine existing wait point classes and appends new rows behind them: a class appears in
 * code only after it was registered here.</p>
 *
 * <p>This batch registers and observes. An observation never loosens or tightens an upper bound and
 * never cancels a wait; a wait at a call site no row covers is counted and listed, not refused. The
 * forced convergence count stays at zero because this batch has no convergence to run.</p>
 */
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
    private final Map<String, String> callSites = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> observedMax = new ConcurrentHashMap<>();
    private final Map<String, Long> progress = new ConcurrentHashMap<>();
    private final Map<String, Integer> walkthrough = new ConcurrentHashMap<>();
    private final Map<String, Integer> unregisteredCallSites = new ConcurrentHashMap<>();
    private final LongAdder waitOverruns = new LongAdder();
    private final LongAdder observations = new LongAdder();
    private final IntSupplier boundMs;

    /**
     * Creates a registry and registers the nine existing wait point classes.
     *
     * @param boundMs upper bound of one wait in milliseconds, read at every observation so a
     *                configuration reload applies without a restart
     */
    public WaitPointRegistry(IntSupplier boundMs) {
        this.boundMs = boundMs;
        registerBuiltins();
    }

    /**
     * Registers one wait point.
     *
     * @param declaration the declaration
     * @return the stored row, a duplicate refusal, or the element that is missing
     */
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

    /**
     * Looks up a registered row.
     *
     * @param wpId the identity
     * @return the row, or {@code null}
     */
    public synchronized WaitPointEntry lookup(String wpId) {
        return wpId == null ? null : rows.get(wpId);
    }

    /**
     * Looks up the row that covers a call site.
     *
     * @param callSiteRef the call site
     * @return the row, or {@code null} when no row covers it
     */
    public WaitPointEntry lookupByCallSite(String callSiteRef) {
        String wpId = callSiteRef == null ? null : callSites.get(callSiteRef);
        return wpId == null ? null : lookup(wpId);
    }

    /**
     * Observes one wait. The registry never changes what the wait does.
     *
     * @param wpId the wait point that covers the wait, or {@code null}
     * @param span the observed wait
     * @return the observation, with the bound verdict and the progress reading
     */
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

    /**
     * Counts a wait at a call site no row covers and puts it on the todo list.
     *
     * @param span the observed wait
     */
    public void noteUnregisteredWait(WaitSpan span) {
        String key = span.callSiteRef() == null || span.callSiteRef().isBlank()
            ? "unknown" : span.callSiteRef();
        unregisteredCallSites.merge(key, 1, Integer::sum);
    }

    /**
     * Records that a fixture walked one wait point through an injection.
     *
     * @param wpId the wait point that was walked through
     */
    public void noteInjectionWalkthrough(String wpId) {
        if (wpId != null) {
            walkthrough.merge(wpId, 1, Integer::sum);
        }
    }

    /**
     * Publishes the coverage self-check.
     *
     * @return the report; the forced convergence count stays zero in this batch
     */
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
        return new CoverageReport(rows.size(), unregisteredCallSites.size(), coverage, walked,
            forcedConvergence(), complete + unregisteredCallSites.size(), pendingElements);
    }

    /** @return the forced convergence count; this batch has none, so it is always zero */
    public long forcedConvergence() {
        return 0L;
    }

    /** @return the number of registered rows */
    public synchronized int registeredTotal() {
        return rows.size();
    }

    /** @return the number of call sites no row covers */
    public int unregisteredCallSites() {
        return unregisteredCallSites.size();
    }

    /** @return the longest wait observed, in milliseconds */
    public long maxWaitMs() {
        long max = 0L;
        for (AtomicLong value : observedMax.values()) {
            max = Math.max(max, value.get());
        }
        return max;
    }

    /** @return waits that exceeded the configured upper bound */
    public long waitOverrunCount() {
        return waitOverruns.sum();
    }

    /** @return wait spans observed */
    public long observationCount() {
        return observations.sum();
    }

    /** @return the last progress reading of each row, keyed by row identity */
    public synchronized Map<String, Long> progressReadings() {
        Map<String, Long> readings = new LinkedHashMap<>();
        for (String wpId : rows.keySet()) {
            readings.put(wpId, progress.getOrDefault(wpId, 0L));
        }
        return readings;
    }

    /** @return the call sites that carry an unregistered wait, with their counts */
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
}
