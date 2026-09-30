/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import java.util.List;

/**
 * One published metering window.
 *
 * <p>The window carries the rows, the completeness count and the three self-monitoring values that
 * prove the observation is not the bottleneck: the sample rate actually achieved, the number of
 * samples the rings had to drop and the time observation itself spent. Wait time is reported next
 * to the classes and never inside one of them.</p>
 *
 * @param tickIndex        tick the window was published at
 * @param windowTicks      ticks the window covers
 * @param warmup           whether the window is still leading warm-up
 * @param rows             one row per class, zero values included
 * @param missingClasses   classes without a row; must stay zero
 * @param sampleRate       retained samples divided by recorded samples
 * @param lostSamples      samples the rings had to drop
 * @param observeMs        time observation itself spent, in milliseconds
 * @param unclassifiedMs   difference that fell into the catch-all row
 * @param totalMs          sum of every row, in milliseconds
 * @param waitTotalMs      wait time observed next to the classes, in milliseconds
 * @param waitMaxMs        longest single wait observed
 * @param waitObservations wait spans observed
 */
public record MeterWindow(long tickIndex, int windowTicks, boolean warmup, List<SelfRow> rows,
                          int missingClasses, double sampleRate, long lostSamples, double observeMs,
                          double unclassifiedMs, double totalMs, double waitTotalMs,
                          double waitMaxMs, long waitObservations) {

    /**
     * Finds one row.
     *
     * @param selfClass the class to look for
     * @return the row, or {@code null} when the window does not carry it
     */
    public SelfRow row(SelfClass selfClass) {
        for (SelfRow row : rows) {
            if (row.selfClass() == selfClass) {
                return row;
            }
        }
        return null;
    }

    /** @return the number of rows published */
    public int rowCount() {
        return rows.size();
    }
}
