/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;

import java.util.ArrayList;
import java.util.List;

/** The offline health aggregation of the entity, block-entity and AI self rows. Every class keeps
 * a row even when its sample is zero; a class the window cannot describe is named in missing and
 * is never silently folded into zero. The view reads a MeterWindow and opens no ownership entry. */
public final class EntityClassHealth {

    /** The three self rows this view owns. */
    public static final List<SelfClass> CLASSES = List.of(SelfClass.ENTITY,
        SelfClass.BLOCKENTITY, SelfClass.AI);

    /** One class's health row. {@code missing} means the window had no row for it at all. */
    public record Row(SelfClass selfClass, long totalNanos, double sharePct, long p50Nanos,
                      long p99Nanos, long samples, long lost, boolean zero, boolean missing) {
    }

    /** The aggregate: complete only when all three rows were present; healthy also needs no lost
     * sample. A complete all-zero window is a statement, not an observation failure. */
    public record Health(long tickIndex, int windowTicks, List<Row> rows,
                         List<SelfClass> missing, List<SelfClass> zero, int measuredClasses,
                         long lostSamples, boolean complete, boolean healthy) {
        public Health {
            rows = List.copyOf(rows);
            missing = List.copyOf(missing);
            zero = List.copyOf(zero);
        }
    }

    private EntityClassHealth() {
    }

    /** Aggregates the three rows of one timer window; a null window is a complete miss. */
    public static Health from(SelfTimers.MeterWindow window) {
        List<Row> rows = new ArrayList<>(CLASSES.size());
        List<SelfClass> missing = new ArrayList<>();
        List<SelfClass> zero = new ArrayList<>();
        long lost = 0L;
        int measured = 0;
        for (SelfClass selfClass : CLASSES) {
            SelfTimers.SelfRow row = window == null ? null : window.row(selfClass);
            if (row == null) {
                missing.add(selfClass);
                rows.add(new Row(selfClass, 0L, 0.0, 0L, 0L, 0L, 0L, true, true));
                continue;
            }
            measured++;
            lost += row.lost();
            boolean isZero = row.totalNanos() == 0L && row.samples() == 0L && row.lost() == 0L;
            if (isZero) {
                zero.add(selfClass);
            }
            rows.add(new Row(selfClass, row.totalNanos(), row.sharePct(), row.p50Nanos(),
                row.p99Nanos(), row.samples(), row.lost(), isZero, false));
        }
        boolean complete = missing.isEmpty();
        return new Health(window == null ? -1L : window.tickIndex(),
            window == null ? 0 : window.windowTicks(), rows, missing, zero, measured, lost,
            complete, complete && lost == 0L);
    }
}
