/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Observation-only C1 census: one row per class/modid/trait key with observed, claimed, refused,
 * fallback and host-time counters. It is written on the tick thread and read as evidence only;
 * it changes no decision and adds no formal reading key.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

import java.util.Map;

/** Per-class/modid/trait C1 counters; class-level recording is also the test seam. */
public final class C1Census {

    public enum Outcome {
        OBSERVED, CLAIMED, REFUSED, FALLBACK, HOST_RUN
    }

    private static final int MAX_KEYS = 128;
    private static final String DUMP_PATH = System.getProperty("arclight.prts.c1CensusPath");
    private static final Map<String, Counters> BY_KEY = new Object2ObjectOpenHashMap<>();
    private static long c0Rows;
    private static long otherRows;

    static {
        if (DUMP_PATH != null && !DUMP_PATH.isEmpty()) {
            System.out.println("[PRTS] c1-census dump path=" + DUMP_PATH);
            Runtime.getRuntime().addShutdownHook(new Thread(C1Census::dump, "prts-c1-census-dump"));
        }
    }

    private C1Census() {
    }

    /** Counts one row that reached the plan point; C0 players are counted separately. */
    public static void observe(Entity entity) {
        OBSERVE_CALLS.increment();
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile == null) {
            PROFILE_NULL.increment();
            // Observation-only fallback: an unclassifiable row still gets a C1 row and is booked
            // as refused, so the census stays accounted without changing any claim/decision.
            String key = entity.getClass().getName() + "|unresolved|0";
            Counters counters = counters(key);
            counters.observed++;
            counters.refused++;
            return;
        }
        if ((profile.traits() & EntityTraitClassifier.T_C0_PLAYER) != 0L) {
            c0Rows++;
            return;
        }
        counters(key(profile)).observed++;
    }

    public static void claimed(Entity entity) {
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(key(profile)).claimed++;
        }
    }

    public static void refused(Entity entity) {
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(key(profile)).refused++;
        }
    }

    public static void fallback(Entity entity) {
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(key(profile)).fallback++;
        }
    }

    /** Class-level recording seam; the key uses the same class/modid/trait shape. */
    /** Adds the observed host time of one row to its class/modid/trait key. */
    public static void hostRun(Entity entity, long hostNanos) {
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            Counters counters = counters(key(profile));
            counters.hostRuns++;
            counters.hostNanos += Math.max(0L, hostNanos);
        }
    }

    private static final java.util.concurrent.atomic.LongAdder RECORD_CALLS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OBSERVE_CALLS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder PROFILE_NULL = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder CLASSIFY_THROW = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SNAPSHOT_CALLS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder RESET_CALLS = new java.util.concurrent.atomic.LongAdder();
    private static volatile String FIRST_CLASSIFY_ERROR;
    private static volatile boolean DUMPED;

    public static long recordCalls() {
        return RECORD_CALLS.sum();
    }

    /** Test-only observe/profile diagnostic line; no formal reading key. */
    public static String observeDiagnostic() {
        return "observeCalls=" + OBSERVE_CALLS.sum() + " profileNull=" + PROFILE_NULL.sum()
            + " classifyThrow=" + CLASSIFY_THROW.sum() + " recordCalls=" + RECORD_CALLS.sum()
            + " snapshotCalls=" + SNAPSHOT_CALLS.sum() + " resetCalls=" + RESET_CALLS.sum()
            + " firstClassifyError=" + FIRST_CLASSIFY_ERROR;
    }

    public static void record(Class<?> type, String modId, long traits, Outcome outcome, long hostNanos) {
        RECORD_CALLS.increment();
        if ((traits & EntityTraitClassifier.T_C0_PLAYER) != 0L) {
            c0Rows++;
            return;
        }
        String key = type.getName() + '|' + modId + '|' + Long.toHexString(traits);
        Counters counters = counters(key);
        switch (outcome) {
            case OBSERVED -> counters.observed++;
            case CLAIMED -> counters.claimed++;
            case REFUSED -> counters.refused++;
            case FALLBACK -> counters.fallback++;
            case HOST_RUN -> counters.hostRuns++;
        }
        counters.hostNanos += Math.max(0L, hostNanos);
    }

    /** One immutable snapshot of the census; used by the test/evidence harness. */
    public record Row(String key, long observed, long claimed, long refused, long fallback,
                      long hostRuns, long hostNanos) {
    }

    public record Snapshot(long observed, long claimed, long refused, long fallback, long hostRuns,
                           long hostNanos, long unaccounted, long c0, long other, java.util.List<Row> rows) {
    }

    public static Snapshot snapshot() {
        SNAPSHOT_CALLS.increment();
        long observed = 0L, claimed = 0L, refused = 0L, fallback = 0L, hostRuns = 0L, hostNanos = 0L;
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, Counters> entry : BY_KEY.entrySet()) {
            Counters c = entry.getValue();
            observed += c.observed; claimed += c.claimed; refused += c.refused;
            fallback += c.fallback; hostRuns += c.hostRuns; hostNanos += c.hostNanos;
            rows.add(new Row(entry.getKey(), c.observed, c.claimed, c.refused, c.fallback,
                c.hostRuns, c.hostNanos));
        }
        return new Snapshot(observed, claimed, refused, fallback, hostRuns, hostNanos,
            Math.max(0L, observed - claimed - refused), c0Rows, otherRows, java.util.List.copyOf(rows));
    }

    /** One evidence line; no formal reading key is added to DomainReadings. */
    public static String evidenceLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-c1:");
        int count = 0;
        for (Map.Entry<String, Counters> entry : BY_KEY.entrySet()) {
            if (count++ > 0) {
                builder.append(';');
            }
            Counters value = entry.getValue();
            builder.append(entry.getKey()).append('=')
                .append(value.observed).append(',')
                .append(value.claimed).append(',')
                .append(value.refused).append(',')
                .append(value.fallback).append(',')
                .append(value.hostRuns).append(',')
                .append(value.hostNanos);
        }
        builder.append(" c0=").append(c0Rows).append(" other=").append(otherRows);
        return builder.toString();
    }

    /** Test-only snapshot dump; enabled by a JVM property, no DomainReadings key. */
    public static void dump() {
        if (DUMP_PATH == null || DUMP_PATH.isEmpty() || DUMPED) {
            return;
        }
        DUMPED = true;
        try {
            Snapshot s = snapshot();
            StringBuilder b = new StringBuilder("{\"observed\":").append(s.observed())
                .append(",\"claimed\":").append(s.claimed())
                .append(",\"refused\":").append(s.refused())
                .append(",\"fallback\":").append(s.fallback())
                .append(",\"hostRuns\":").append(s.hostRuns())
                .append(",\"hostNanos\":").append(s.hostNanos())
                .append(",\"unaccounted\":").append(s.unaccounted())
                .append(",\"c0\":").append(s.c0())
                .append(",\"other\":").append(s.other())
                .append(",\"observeDiag\":\"").append(observeDiagnostic().replace("\"","'")).append("\",\"rows\":[");
            int i = 0;
            for (Row r : s.rows()) {
                if (i++ > 0) b.append(',');
                b.append("{\"key\":\"").append(r.key().replace("\"","'")).append("\",\"observed\":")
                    .append(r.observed()).append(",\"claimed\":").append(r.claimed())
                    .append(",\"refused\":").append(r.refused()).append(",\"fallback\":")
                    .append(r.fallback()).append(",\"hostRuns\":").append(r.hostRuns())
                    .append(",\"hostNanos\":").append(r.hostNanos()).append('}');
            }
            b.append("]}\n");
            java.nio.file.Files.writeString(java.nio.file.Path.of(DUMP_PATH), b.toString());
        } catch (Throwable ignored) {
        }
    }

    public static void reset() {
        RESET_CALLS.increment();
        BY_KEY.clear();
        c0Rows = 0L;
        otherRows = 0L;
    }

    private static EntityTraitClassifier.Profile profileOf(Entity entity) {
        try {
            return EntityTraitClassifier.classify(entity);
        } catch (Throwable throwable) {
            CLASSIFY_THROW.increment();
            if (FIRST_CLASSIFY_ERROR == null) {
                FIRST_CLASSIFY_ERROR = throwable.getClass().getName() + ": " + String.valueOf(throwable.getMessage());
            }
            return null;
        }
    }

    private static String key(EntityTraitClassifier.Profile profile) {
        return profile.className() + '|' + profile.modId() + '|' + Long.toHexString(profile.traits());
    }

    private static Counters counters(String key) {
        Counters existing = BY_KEY.get(key);
        if (existing != null) {
            return existing;
        }
        if (BY_KEY.size() >= MAX_KEYS) {
            otherRows++;
            return new Counters();
        }
        Counters created = new Counters();
        BY_KEY.put(key, created);
        return created;
    }

    private static final class Counters {
        long observed;
        long claimed;
        long refused;
        long fallback;
        long hostRuns;
        long hostNanos;
    }
}
