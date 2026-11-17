/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The per-class census of the supported set: for every class name it counts the rows seen, the rows
 * admitted to an owner program, the rows completed, the rows that stayed at the C1 fallback and the
 * rows outside the support matrix. The census is evidence only: no decision reads it, it publishes
 * no readout key, and it is written on the tick thread.
 */
public final class SupportCensus {

    /** What one class was counted as; only a supported class may be admitted and completed. */
    public enum Outcome {
        SEEN,
        ADMITTED,
        COMPLETED,
        FALLBACK,
        OUTSIDE
    }

    /** The five counters of one class. */
    public record Counts(long seen, long admitted, long completed, long fallback, long outside) {
    }

    private static final Map<String, LongAdder[]> COUNTERS = new ConcurrentHashMap<>();

    private SupportCensus() {
    }

    /** Counts one outcome for one class. */
    public static void note(String className, Outcome outcome) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(outcome, "outcome");
        COUNTERS.computeIfAbsent(className, key -> {
            LongAdder[] adders = new LongAdder[Outcome.values().length];
            for (int index = 0; index < adders.length; index++) {
                adders[index] = new LongAdder();
            }
            return adders;
        })[outcome.ordinal()].increment();
    }

    /** The counts of one class. */
    public static Counts counts(String className) {
        LongAdder[] adders = COUNTERS.get(className);
        if (adders == null) {
            return new Counts(0L, 0L, 0L, 0L, 0L);
        }
        return new Counts(adders[Outcome.SEEN.ordinal()].sum(),
            adders[Outcome.ADMITTED.ordinal()].sum(),
            adders[Outcome.COMPLETED.ordinal()].sum(),
            adders[Outcome.FALLBACK.ordinal()].sum(),
            adders[Outcome.OUTSIDE.ordinal()].sum());
    }

    /** Every class the census saw, in a stable order, with its counts. */
    public static Map<String, Counts> snapshot() {
        Map<String, Counts> snapshot = new LinkedHashMap<>();
        COUNTERS.keySet().stream().sorted().forEach(name -> snapshot.put(name, counts(name)));
        return Map.copyOf(snapshot);
    }

    /** Drops every count; test and evidence code only. */
    public static void reset() {
        COUNTERS.clear();
    }

    /** The total counts over every class. */
    public static Counts totals() {
        long seen = 0L;
        long admitted = 0L;
        long completed = 0L;
        long fallback = 0L;
        long outside = 0L;
        for (String name : COUNTERS.keySet()) {
            Counts counts = counts(name);
            seen += counts.seen();
            admitted += counts.admitted();
            completed += counts.completed();
            fallback += counts.fallback();
            outside += counts.outside();
        }
        return new Counts(seen, admitted, completed, fallback, outside);
    }
}
