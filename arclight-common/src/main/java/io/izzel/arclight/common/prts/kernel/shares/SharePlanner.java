/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Plans the time budget of one tick and turns overruns into readable verdicts.
 *
 * <p>Planning is a pure function of the world list, the tick index and the metered work: it reads
 * no clock, so two runs with the same input produce the same table. Metering consumes the sums the
 * per-class timer already keeps, which is why the share table has no metering point of its own.</p>
 *
 * <p>This batch records what a degradation would do and never does it: the executed flag of every
 * overrun record stays false, the reserved pool may only be drawn by its two entries, and the class
 * and the world overrun counters always carry the same total in both dimensions.</p>
 */
public final class SharePlanner {

    /** Overrun records kept for the readout; older ones are dropped, the counters stay. */
    private static final int RECORD_LIMIT = 256;

    private final Map<ShareClass, LongAdder> classOverruns = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> worldOverruns = new ConcurrentHashMap<>();
    private final Map<ReservePurpose, Double> reserveUsedByPurpose = new EnumMap<>(ReservePurpose.class);
    private final List<OverrunRecord> records = new ArrayList<>();
    private final LongAdder hungerEvents = new LongAdder();
    private final LongAdder reserveBorrowed = new LongAdder();
    private double reserveUsed;
    private volatile ShareTable lastTable;

    /**
     * Plans the table of one tick.
     *
     * @param worlds    worlds the tick carries
     * @param tickIndex tick the table is planned for
     * @param usedMs    metered work per world and class; missing entries count as zero
     * @return the planned table
     */
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

    /**
     * Recomputes the budget conservation of a table.
     *
     * @param table the table to check
     * @return whether the budget holds, and by how much it is over when it does not
     */
    public ConservationCheck checkConservation(ShareTable table) {
        if (table == null) {
            return new ConservationCheck(false, "table", 0.0);
        }
        double planned = table.sumSharesMs() + table.reserve().reserveMs() + table.hostOverheadMs();
        if (planned <= table.eBudgetMs()) {
            return ConservationCheck.holds();
        }
        return new ConservationCheck(false, "time budget", planned - table.eBudgetMs());
    }

    /**
     * Counts an overrun and answers what a degradation would do.
     *
     * @param row    the row that overspent
     * @param siteId the site that was charged
     * @return the verdict, or {@code null} when the row is inside its share
     */
    public WouldDegrade noteOverrun(ShareTable.ShareRow row, String siteId) {
        if (row == null || !row.overrun()) {
            return null;
        }
        classOverruns.computeIfAbsent(row.shareClass(), key -> new LongAdder()).increment();
        worldOverruns.computeIfAbsent(row.worldId(), key -> new LongAdder()).increment();
        return new WouldDegrade(row.worldId(), row.shareClass(), true, levelFor(row.shareClass()));
    }

    /**
     * Records what a degradation would have done. The action itself is never executed here.
     *
     * @param record the record to keep
     */
    public synchronized void recordWouldDegrade(OverrunRecord record) {
        if (records.size() >= RECORD_LIMIT) {
            records.remove(0);
        }
        records.add(record);
    }

    /**
     * Turns an overrun row into the record shape without executing it.
     *
     * @param row       the row that overspent
     * @param siteId    the site that was charged
     * @param tickIndex tick the overrun happened at
     * @return the record, with the executed flag false, or {@code null} when the row is inside
     */
    public OverrunRecord record(ShareTable.ShareRow row, String siteId, long tickIndex) {
        WouldDegrade verdict = noteOverrun(row, siteId);
        if (verdict == null) {
            return null;
        }
        return new OverrunRecord(tickIndex, row.worldId(), row.shareClass(), siteId,
            classOverrunCount(row.shareClass()), worldOverrunCount(row.worldId()),
            verdict.level(), false);
    }

    /**
     * Records a hunger event.
     *
     * @param worldId the world that did not receive a share for the window
     */
    public void noteHunger(String worldId) {
        hungerEvents.increment();
    }

    /**
     * Draws from the reserved pool.
     *
     * @param purpose one of the two entries the pool may pay for
     * @param millis  the amount
     */
    public synchronized void consumeReserve(ReservePurpose purpose, double millis) {
        if (purpose == null) {
            throw new IllegalArgumentException("the reserved pool needs a purpose");
        }
        reserveUsed += millis;
        reserveUsedByPurpose.merge(purpose, millis, Double::sum);
    }

    /**
     * Counts a draw from the reserved pool that did not come from one of its two entries.
     *
     * @param millis the amount that was borrowed
     */
    public void noteReserveBorrowed(double millis) {
        reserveBorrowed.increment();
    }

    /**
     * Returns the level a class would degrade to.
     *
     * @param shareClass the class that overspent
     * @return the fixed level of that class
     */
    public static DegradeLevel levelFor(ShareClass shareClass) {
        return switch (shareClass) {
            case AI -> DegradeLevel.B1;
            case GRAPH -> DegradeLevel.B2;
            case ENTITY, BLOCKENTITY, REGIONTICK -> DegradeLevel.B3;
            case EVENT -> DegradeLevel.B4;
            case WORLDRES, OTHER -> DegradeLevel.B5;
        };
    }

    /**
     * Converts the per-world tick totals of the timer into per-class sums.
     *
     * @param tickTotals world to per-class nanoseconds, as the timer publishes it
     * @return world to per-class milliseconds, only for classes that own a share row
     */
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

    /** @return the table planned last */
    public ShareTable lastTable() {
        return lastTable;
    }

    /**
     * Returns the class dimension overrun count of one class.
     *
     * @param shareClass the class
     * @return the count
     */
    public long classOverrunCount(ShareClass shareClass) {
        LongAdder counter = classOverruns.get(shareClass);
        return counter == null ? 0L : counter.sum();
    }

    /**
     * Returns the world dimension overrun count of one world.
     *
     * @param worldId the world
     * @return the count
     */
    public long worldOverrunCount(String worldId) {
        LongAdder counter = worldOverruns.get(worldId);
        return counter == null ? 0L : counter.sum();
    }

    /** @return class dimension total */
    public long classOverrunTotal() {
        long total = 0L;
        for (LongAdder counter : classOverruns.values()) {
            total += counter.sum();
        }
        return total;
    }

    /** @return world dimension total; equals the class dimension total by construction */
    public long worldOverrunTotal() {
        long total = 0L;
        for (LongAdder counter : worldOverruns.values()) {
            total += counter.sum();
        }
        return total;
    }

    /** @return the worlds that overspent, in insertion order */
    public List<String> overrunWorlds() {
        return new ArrayList<>(worldOverruns.keySet());
    }

    /** @return hunger events recorded */
    public long hungerEventCount() {
        return hungerEvents.sum();
    }

    /** @return draws from the reserved pool that came from somewhere else; must stay zero */
    public long reserveBorrowedCount() {
        return reserveBorrowed.sum();
    }

    /** @return what the reserved pool has been used for */
    public Map<ReservePurpose, Double> reserveUsedByPurpose() {
        return Map.copyOf(reserveUsedByPurpose);
    }

    /** @return the recorded overruns, oldest first */
    public synchronized List<OverrunRecord> records() {
        return List.copyOf(records);
    }

    /** @return whether any recorded overrun executed an action; must stay false */
    public synchronized boolean anyActionExecuted() {
        for (OverrunRecord record : records) {
            if (record.actionExecuted()) {
                return true;
            }
        }
        return false;
    }
}
