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
    private static final Map<String, Counters> BY_KEY = new Object2ObjectOpenHashMap<>();
    private static long c0Rows;
    private static long otherRows;

    private C1Census() {
    }

    /** Counts one row that reached the plan point; C0 players are counted separately. */
    public static void observe(Entity entity) {
        EntityTraitClassifier.Profile profile = profileOf(entity);
        if (profile == null) {
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

    public static void record(Class<?> type, String modId, long traits, Outcome outcome, long hostNanos) {
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

    public static void reset() {
        BY_KEY.clear();
        c0Rows = 0L;
        otherRows = 0L;
    }

    private static EntityTraitClassifier.Profile profileOf(Entity entity) {
        try {
            return EntityTraitClassifier.classify(entity);
        } catch (Throwable ignored) {
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
