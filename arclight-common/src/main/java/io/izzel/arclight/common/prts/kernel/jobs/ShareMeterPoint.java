/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The metering point of the job layer. It records into the same per-class self timers the share
 * table is planned from and answers its readings through the metering reading of the share layer, so
 * the job layer has no second conversion from samples to milliseconds: a class costs the same number
 * here, in the table and in the readout, or the three disagree. */
public final class ShareMeterPoint {

    private final Set<SelfClass> touched = EnumSet.noneOf(SelfClass.class);
    private long notes;
    private long nanos;
    private long refused;

    /** Records the cost of one job under the timer row its declaration named. A sample without a row
     * or without cost is refused and counted instead of being booked onto a class nobody named. */
    public void note(SelfClass selfClass, String worldId, String siteId, long costNanos) {
        if (selfClass == null || costNanos <= 0L) {
            refused++;
            return;
        }
        SelfTimers.note(selfClass, worldId == null ? "" : worldId, siteId == null ? "" : siteId,
            costNanos);
        touched.add(selfClass);
        notes++;
        nanos += costNanos;
    }

    /** The conversion of the share layer, reached through this point so a reader of the job layer
     * never has to know which type owns it. */
    public static Map<String, EnumMap<ShareClass, Double>> usedFromTickTotals(
        Map<String, long[]> tickTotals) {
        return ShareMeter.usedFromTickTotals(tickTotals);
    }

    /** The per-class reading of one tick, again through the share layer. */
    public static ShareMeter.TickReading read(long tickIndex, boolean timerEnabled,
                                              Map<String, long[]> tickTotals) {
        return ShareMeter.read(tickIndex, timerEnabled, tickTotals);
    }

    /** The share class a timer row is metered under. */
    public static ShareClass shareClassOf(SelfClass selfClass) {
        return ShareClass.of(selfClass);
    }

    public long notes() {
        return notes;
    }

    public long nanos() {
        return nanos;
    }

    public long refusedNotes() {
        return refused;
    }

    /** The number of distinct timer rows this point has written into. */
    public int classes() {
        return touched.size();
    }

    public void reset() {
        touched.clear();
        notes = 0L;
        nanos = 0L;
        refused = 0L;
    }
}
