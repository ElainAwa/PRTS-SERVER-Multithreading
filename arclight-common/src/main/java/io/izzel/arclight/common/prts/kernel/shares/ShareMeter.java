/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The single conversion from the per-class self timers to the share rows. Every share class
 * publishes a row, zero values included, and the timer rows that deliberately feed no share class
 * are named instead of dropped: a class without a reading would otherwise look like a class without
 * cost. */
public final class ShareMeter {

    /** One class of one tick: the metered milliseconds, the raw samples behind them and the timer
     * rows that fed it. */
    public record ClassReading(ShareClass shareClass, double usedMs, long sampleNanos,
                               List<SelfClass> sources) {
    }

    /** One tick of metering. A complete reading carries exactly one row per share class; a share
     * class the timer cannot describe is listed in {@code missing} rather than left out. */
    public record TickReading(long tickIndex, boolean timerEnabled, List<ClassReading> rows,
                              List<SelfClass> unmapped, List<ShareClass> missing) {

        public ClassReading row(ShareClass shareClass) {
            for (ClassReading row : rows) {
                if (row.shareClass() == shareClass) {
                    return row;
                }
            }
            return null;
        }

        public int rowCount() {
            return rows.size();
        }

        public boolean complete() {
            return missing.isEmpty() && rows.size() == ShareClass.rowCount();
        }
    }

    private ShareMeter() {
    }

    /** Converts the per-world tick totals of the timer into per-class milliseconds. */
    public static Map<String, EnumMap<ShareClass, Double>> usedFromTickTotals(
        Map<String, long[]> tickTotals) {
        Map<String, EnumMap<ShareClass, Double>> used = new LinkedHashMap<>();
        if (tickTotals == null) {
            return used;
        }
        SelfClass[] classes = SelfClass.values();
        for (Map.Entry<String, long[]> entry : tickTotals.entrySet()) {
            EnumMap<ShareClass, Double> perClass = new EnumMap<>(ShareClass.class);
            long[] totals = entry.getValue();
            for (int index = 0; index < classes.length && index < totals.length; index++) {
                ShareClass shareClass = ShareClass.of(classes[index]);
                if (shareClass == null || totals[index] == 0L) {
                    continue;
                }
                perClass.merge(shareClass, totals[index] / 1_000_000.0, Double::sum);
            }
            used.put(entry.getKey(), perClass);
        }
        return used;
    }

    /** Reads every share class of one tick, summed over the worlds. */
    public static TickReading read(long tickIndex, boolean timerEnabled,
                                   Map<String, long[]> tickTotals) {
        long[] sums = new long[SelfClass.values().length];
        if (tickTotals != null) {
            for (long[] totals : tickTotals.values()) {
                for (int index = 0; index < sums.length && index < totals.length; index++) {
                    sums[index] += totals[index];
                }
            }
        }
        List<ClassReading> rows = new ArrayList<>(ShareClass.rowCount());
        List<ShareClass> missing = new ArrayList<>();
        for (ShareClass shareClass : ShareClass.values()) {
            long nanos = 0L;
            List<SelfClass> sources = new ArrayList<>();
            for (SelfClass selfClass : SelfClass.values()) {
                if (ShareClass.of(selfClass) != shareClass) {
                    continue;
                }
                sources.add(selfClass);
                nanos += sums[selfClass.ordinal()];
            }
            if (sources.isEmpty()) {
                missing.add(shareClass);
            }
            rows.add(new ClassReading(shareClass, nanos / 1_000_000.0, nanos, List.copyOf(sources)));
        }
        List<SelfClass> unmapped = new ArrayList<>();
        for (SelfClass selfClass : SelfClass.values()) {
            if (ShareClass.of(selfClass) == null) {
                unmapped.add(selfClass);
            }
        }
        return new TickReading(tickIndex, timerEnabled, List.copyOf(rows), List.copyOf(unmapped),
            List.copyOf(missing));
    }
}
