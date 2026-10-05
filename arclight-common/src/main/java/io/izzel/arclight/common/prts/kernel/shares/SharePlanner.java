/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable.ReservePurpose;

/** Planning is a pure function of the world list, the tick index and the metered work: it reads no
 * clock, so two runs with the same input produce the same table. */
public final class SharePlanner {

    private static final int RECORD_LIMIT = 256;

    private final Map<ShareClass, LongAdder> classOverruns = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> worldOverruns = new ConcurrentHashMap<>();
    private final Map<ReservePurpose, Double> reserveUsedByPurpose = new EnumMap<>(ReservePurpose.class);
    private final List<OverrunRecord> records = new ArrayList<>();
    private final LongAdder hungerEvents = new LongAdder();
    private final LongAdder reserveBorrowed = new LongAdder();
    private double reserveUsed;
    private volatile ShareTable lastTable;

    /** Plans the table of one tick. */
    public ShareTable plan(List<String> worlds, long tickIndex,
                           Map<String, EnumMap<ShareClass, Double>> usedMs) {
        double worldShare = KernelSettings.worldShareMs();
        List<ShareTable.ShareRow> rows = new ArrayList<>();
        for (String world : worlds) {
            EnumMap<ShareClass, Double> used = usedMs == null ? null : usedMs.get(world);
            for (ShareClass shareClass : ShareClass.values()) {
                double spent = used == null ? 0.0 : used.getOrDefault(shareClass, 0.0);
                rows.add(new ShareTable.ShareRow(world, shareClass,
                    worldShare * shareClass.weight(), spent));
            }
        }
        ShareTable.ReserveRow reserve = new ShareTable.ReserveRow(KernelSettings.reserveMs(),
            reserveUsed, Map.copyOf(reserveUsedByPurpose));
        ShareTable table = new ShareTable(tickIndex, List.copyOf(rows), reserve,
            KernelSettings.hostOverheadMs(), KernelSettings.eBudgetMs());
        lastTable = table;
        return table;
    }

    /** Recomputes the budget conservation of a table. */
    public ConservationCheck checkConservation(ShareTable table) {
        if (table == null) {
            return ConservationCheck.unplanned();
        }
        double shares = table.sumSharesMs();
        double reserve = table.reserve().reserveMs();
        double host = table.hostOverheadMs();
        double planned = shares + reserve + host;
        double budget = table.eBudgetMs();
        double slack = budget - planned;
        ConservationCheck.Verdict verdict;
        String item = "";
        if (slack < 0.0) {
            verdict = ConservationCheck.Verdict.OVER;
            item = "time budget";
        } else if (slack <= budget * ConservationCheck.CRITICAL_SLACK_FRACTION) {
            verdict = ConservationCheck.Verdict.CRITICAL;
            item = "slack";
        } else {
            verdict = ConservationCheck.Verdict.OK;
        }
        return new ConservationCheck(true, verdict, item, shares, reserve, host, planned, budget,
            slack);
    }

    /** Counts an overrun and answers what a degradation would do. */
    public WouldDegrade noteOverrun(ShareTable.ShareRow row, String siteId) {
        if (row == null || !row.overrun()) {
            return null;
        }
        classOverruns.computeIfAbsent(row.shareClass(), key -> new LongAdder()).increment();
        worldOverruns.computeIfAbsent(row.worldId(), key -> new LongAdder()).increment();
        return new WouldDegrade(row.worldId(), row.shareClass(), true, levelFor(row.shareClass()));
    }

    /** Records what a degradation would have done. */
    public synchronized void recordWouldDegrade(OverrunRecord record) {
        if (records.size() >= RECORD_LIMIT) {
            records.remove(0);
        }
        records.add(record);
    }

    /** Turns an overrun row into the record shape without executing it. */
    public OverrunRecord record(ShareTable.ShareRow row, String siteId, long tickIndex) {
        WouldDegrade verdict = noteOverrun(row, siteId);
        if (verdict == null) {
            return null;
        }
        return new OverrunRecord(tickIndex, row.worldId(), row.shareClass(), siteId,
            classOverrunCount(row.shareClass()), worldOverrunCount(row.worldId()),
            verdict.level(), false);
    }

    /** Records a hunger event. */
    public void noteHunger(String worldId) {
        hungerEvents.increment();
    }

    /** Draws from the reserved pool. */
    public synchronized void consumeReserve(ReservePurpose purpose, double millis) {
        if (purpose == null) {
            throw new IllegalArgumentException("the reserved pool needs a purpose");
        }
        reserveUsed += millis;
        reserveUsedByPurpose.merge(purpose, millis, Double::sum);
    }

    /** Counts a draw from the reserved pool that did not come from one of its two entries. */
    public void noteReserveBorrowed(double millis) {
        reserveBorrowed.increment();
    }

    /** Returns the level a class would degrade to. */
    public static DegradeLevel levelFor(ShareClass shareClass) {
        return switch (shareClass) {
            case AI -> DegradeLevel.B1;
            case GRAPH -> DegradeLevel.B2;
            case ENTITY, BLOCKENTITY, REGIONTICK -> DegradeLevel.B3;
            case EVENT -> DegradeLevel.B4;
            case WORLDRES, OTHER -> DegradeLevel.B5;
        };
    }

    /** Converts the per-world tick totals of the timer into per-class sums. The conversion itself
     * lives with the metering reading, so the table and the readout can never disagree about what a
     * class cost. */
    public static Map<String, EnumMap<ShareClass, Double>> usedFromTickTotals(
        Map<String, long[]> tickTotals) {
        return ShareMeter.usedFromTickTotals(tickTotals);
    }

    public ShareTable lastTable() {
        return lastTable;
    }

    /** Returns the class dimension overrun count of one class. */
    public long classOverrunCount(ShareClass shareClass) {
        LongAdder counter = classOverruns.get(shareClass);
        return counter == null ? 0L : counter.sum();
    }

    /** Returns the world dimension overrun count of one world. */
    public long worldOverrunCount(String worldId) {
        LongAdder counter = worldOverruns.get(worldId);
        return counter == null ? 0L : counter.sum();
    }

    public long classOverrunTotal() {
        long total = 0L;
        for (LongAdder counter : classOverruns.values()) {
            total += counter.sum();
        }
        return total;
    }

    public long worldOverrunTotal() {
        long total = 0L;
        for (LongAdder counter : worldOverruns.values()) {
            total += counter.sum();
        }
        return total;
    }

    public List<String> overrunWorlds() {
        return new ArrayList<>(worldOverruns.keySet());
    }

    public long hungerEventCount() {
        return hungerEvents.sum();
    }

    public long reserveBorrowedCount() {
        return reserveBorrowed.sum();
    }

    public Map<ReservePurpose, Double> reserveUsedByPurpose() {
        return Map.copyOf(reserveUsedByPurpose);
    }

    public synchronized List<OverrunRecord> records() {
        return List.copyOf(records);
    }

    public synchronized boolean anyActionExecuted() {
        for (OverrunRecord record : records) {
            if (record.actionExecuted()) {
                return true;
            }
        }
        return false;
    }

    /** What a degradation would do about an overrun row. This batch never executes the level it names:
     * the value exists so the readout can state what the next batch would enter, and the record next
     * to it carries the executed flag that stays false. */
    public record WouldDegrade(String worldId, ShareClass overClass, boolean overWorld,
                               DegradeLevel level) {
    }

    /** The result of recomputing the budget conservation of one table. The check is an equation, not a
     * memory: the sum of every class share, the reserved column and the fixed host overhead has to fit
     * into the budget of the tick, and all three terms are carried so the equation can be recomputed
     * from the reading alone. */
    public record ConservationCheck(boolean planned, Verdict verdict, String item, double sumSharesMs,
                                    double reserveMs, double hostOverheadMs, double plannedMs,
                                    double eBudgetMs, double slackMs) {

        /** The band a plan sits in. A plan that fits but leaves this fraction of the budget or less
         * is called out before it crosses into the over band. The fraction is a declared value, not
         * a measured one. */
        public static final double CRITICAL_SLACK_FRACTION = 0.10;

        public enum Verdict {
            OK,
            CRITICAL,
            OVER
        }

        public boolean ok() {
            return planned && verdict != Verdict.OVER;
        }

        public double overByMs() {
            return slackMs < 0.0 ? -slackMs : 0.0;
        }

        /** A table that was never planned: no term exists, and the reading says so instead of
         * publishing zeroes that would look like a plan of zero cost. */
        public static ConservationCheck unplanned() {
            return new ConservationCheck(false, Verdict.OK, "unplanned", 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0);
        }
    }
}
