/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Observation-only entity ownership census: one row per class/modid/trait key with observed, claimed, refused,
 * fallback and host-time counters. It is written on the tick thread and read as evidence only;
 * it changes no decision and adds no formal reading key.
 */

package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import java.util.concurrent.atomic.LongAdder;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;

import java.util.Map;

/** Per-class/modid/trait entity ownership counters; class-level recording is also the test seam. */
public final class EntityOwnershipCensus {

    public enum Outcome {
        OBSERVED, CLAIMED, REFUSED, FALLBACK, HOST_RUN
    }

    private static final int MAX_KEYS = 128;
    private static final String DUMP_PATH = System.getProperty("arclight.prts.entityOwnershipCensusPath");
    /** Class rows hold the per-trait counters; the string key is only built for a readout. */
    private static final Map<String, ClassRow> BY_CLASS = new Object2ObjectOpenHashMap<>();
    private static int entryCount;
    private static long c0Rows;
    private static long otherRows;
    /** Hot per-row counter: the plan point is the only writer, so a plain volatile field is
     * enough (it is read by the evidence thread without a lock). */
    private static volatile long observeCalls;

    static {
        if (DUMP_PATH != null && !DUMP_PATH.isEmpty()) {
            System.out.println("[PRTS] entity-ownership-census dump path=" + DUMP_PATH);
            Runtime.getRuntime().addShutdownHook(
                new Thread(EntityOwnershipCensus::dump, "prts-entity-ownership-census-dump"));
        }
    }

    private EntityOwnershipCensus() {
    }

    /** Counts one row that reached the plan point; C0 players are counted separately. */
    public static void observe(Entity entity) {
        observe(entity, EntityOwnershipCensus.profileOf(entity));
    }

    /** Counts one row whose profile the plan point already classified once for this tick. */
    static void observe(Entity entity, EntityTraitClassifier.Profile profile) {
        observeCalls++;
        if (profile == null) {
            PROFILE_NULL.increment();
            // Observation-only fallback: an unclassifiable row still gets a C1 row and is booked
            // as refused, so the census stays accounted without changing any claim/decision.
            Counters counters = counters(entity.getClass().getName(), "unresolved", 0L);
            counters.observed++;
            counters.refused++;
            return;
        }
        if ((profile.traits() & EntityTraitClassifier.T_C0_PLAYER) != 0L) {
            c0Rows++;
            return;
        }
        counters(profile).observed++;
    }

    public static void claimed(Entity entity) {
        claimed(EntityOwnershipCensus.profileOf(entity));
    }

    static void claimed(EntityTraitClassifier.Profile profile) {
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(profile).claimed++;
        }
    }

    public static void refused(Entity entity) {
        refused(EntityOwnershipCensus.profileOf(entity));
    }

    static void refused(EntityTraitClassifier.Profile profile) {
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(profile).refused++;
        }
    }

    public static void fallback(Entity entity) {
        fallback(EntityOwnershipCensus.profileOf(entity));
    }

    static void fallback(EntityTraitClassifier.Profile profile) {
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            counters(profile).fallback++;
        }
    }

    /** Adds the observed host time of one row to its class/modid/trait key. */
    public static void hostRun(Entity entity, long hostNanos) {
        hostRun(EntityOwnershipCensus.profileOf(entity), hostNanos);
    }

    static void hostRun(EntityTraitClassifier.Profile profile, long hostNanos) {
        if (profile != null && (profile.traits() & EntityTraitClassifier.T_C0_PLAYER) == 0L) {
            Counters counters = counters(profile);
            counters.hostRuns++;
            counters.hostNanos += Math.max(0L, hostNanos);
        }
    }

    private static final LongAdder RECORD_CALLS = new LongAdder();
    private static final LongAdder PROFILE_NULL = new LongAdder();
    private static final LongAdder CLASSIFY_THROW = new LongAdder();
    private static final LongAdder SNAPSHOT_CALLS = new LongAdder();
    private static final LongAdder RESET_CALLS = new LongAdder();
    private static volatile String FIRST_CLASSIFY_ERROR;
    private static volatile boolean DUMPED;

    public static long recordCalls() {
        return RECORD_CALLS.sum();
    }

    /** Test-only observe/profile diagnostic line; no formal reading key. */
    public static String observeDiagnostic() {
        return "observeCalls=" + observeCalls + " profileNull=" + PROFILE_NULL.sum()
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
        Counters counters = counters(type.getName(), modId, traits);
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
        for (ClassRow classRow : BY_CLASS.values()) {
            for (Long2ObjectMap.Entry<Counters> entry : classRow.byTraits.long2ObjectEntrySet()) {
                Counters c = entry.getValue();
                observed += c.observed; claimed += c.claimed; refused += c.refused;
                fallback += c.fallback; hostRuns += c.hostRuns; hostNanos += c.hostNanos;
                rows.add(new Row(key(classRow.className, classRow.modId, entry.getLongKey()),
                    c.observed, c.claimed, c.refused, c.fallback, c.hostRuns, c.hostNanos));
            }
        }
        return new Snapshot(observed, claimed, refused, fallback, hostRuns, hostNanos,
            Math.max(0L, observed - claimed - refused), c0Rows, otherRows, java.util.List.copyOf(rows));
    }

    /** One evidence line; no formal reading key is added to DomainReadings. */
    public static String evidenceLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-ownership:");
        int count = 0;
        for (ClassRow classRow : BY_CLASS.values()) {
            for (Long2ObjectMap.Entry<Counters> entry : classRow.byTraits.long2ObjectEntrySet()) {
                if (count++ > 0) {
                    builder.append(';');
                }
                Counters value = entry.getValue();
                builder.append(key(classRow.className, classRow.modId, entry.getLongKey())).append('=')
                    .append(value.observed).append(',')
                    .append(value.claimed).append(',')
                    .append(value.refused).append(',')
                    .append(value.fallback).append(',')
                    .append(value.hostRuns).append(',')
                    .append(value.hostNanos);
            }
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
        BY_CLASS.clear();
        entryCount = 0;
        observeCalls = 0L;
        c0Rows = 0L;
        otherRows = 0L;
    }

    /** The profile of one row; the plan point classifies once and hands the same profile to every
     * counter of that row, so one tick does not scan the class hierarchy several times per row. */
    static EntityTraitClassifier.Profile profileOf(Entity entity) {
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

    private static Counters counters(EntityTraitClassifier.Profile profile) {
        return counters(profile.className(), profile.modId(), profile.traits());
    }

    /**
     * The counters of one class/modid/trait row. The class is found by the name the profile already
     * carries and the counters by the trait bits, so the per-tick path allocates no key string; the
     * {@code class|mod|traits} key is only composed when a readout walks the census.
     */
    private static Counters counters(String className, String modId, long traits) {
        ClassRow classRow = BY_CLASS.get(className);
        if (classRow == null) {
            if (entryCount >= MAX_KEYS) {
                otherRows++;
                return new Counters();
            }
            classRow = new ClassRow(className, modId);
            BY_CLASS.put(className, classRow);
        }
        Counters existing = classRow.byTraits.get(traits);
        if (existing != null) {
            return existing;
        }
        if (entryCount >= MAX_KEYS) {
            otherRows++;
            return new Counters();
        }
        entryCount++;
        Counters created = new Counters();
        classRow.byTraits.put(traits, created);
        return created;
    }

    private static String key(String className, String modId, long traits) {
        return className + '|' + modId + '|' + Long.toHexString(traits);
    }

    private static final class ClassRow {
        final String className;
        final String modId;
        final Long2ObjectOpenHashMap<Counters> byTraits = new Long2ObjectOpenHashMap<>();

        ClassRow(String className, String modId) {
            this.className = className;
            this.modId = modId;
        }
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
