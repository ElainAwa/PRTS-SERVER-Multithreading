/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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
    private final Map<String, LongAdder> dualOverruns = new ConcurrentHashMap<>();
    private final Map<ReservePurpose, Double> reserveUsedByPurpose = new EnumMap<>(ReservePurpose.class);
    private final Map<String, Integer> hungerStreaks = new HashMap<>();
    private final List<OverrunRecord> records = new ArrayList<>();
    private final Map<OverrunRecord, Long> recordTokens = new java.util.IdentityHashMap<>();
    private long nextRecordToken = 1L;
    private boolean actionExecutedFlag;
    private final LongAdder hungerEvents = new LongAdder();
    private final LongAdder reserveBorrowed = new LongAdder();
    private double reserveUsed;
    private volatile ShareTable lastTable;

    /** The share budget left after the host overhead and the reserved pool. The version that
     * subtracted only the reserve let an eight-world table plan 70 ms against a 50 ms budget. */
    private static double availableShareMs() {
        return Math.max(0.0, KernelSettings.eBudgetMs() - KernelSettings.reserveMs()
            - KernelSettings.hostOverheadMs());
    }

    /** One world's fair share, capped by the declared per-world share. The cap is an upper bound,
     * not a multiplier: eight worlds must divide the available budget, not multiply the cap. */
    private static double fairWorldShare(double fairMs) {
        return Math.min(KernelSettings.worldShareMs(), Math.max(0.0, fairMs));
    }

    /** Plans the table of one tick. */
    public ShareTable plan(List<String> worlds, long tickIndex,
                           Map<String, EnumMap<ShareClass, Double>> usedMs) {
        int count = worlds == null ? 0 : worlds.size();
        double worldShare = count == 0 ? 0.0 : fairWorldShare(availableShareMs() / count);
        List<String> safeWorlds = worlds == null ? List.of() : worlds;
        List<ShareTable.ShareRow> rows = new ArrayList<>();
        for (String world : safeWorlds) {
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

    /** Plans the two-level table of one tick: an activity-weighted world share first, then the
     * class weights inside that world. No activity weight (or a non-positive/empty map) falls back
     * to an equal division, so one world keeps exactly the same rows as {@link #plan}. This is the
     * offline fair-share entry; the production path keeps calling {@link #plan} until a later batch
     * wires it. */
    public ShareTable planFair(List<String> worlds, long tickIndex,
                               Map<String, EnumMap<ShareClass, Double>> usedMs,
                               Map<String, Double> activityWeights) {
        List<String> safeWorlds = worlds == null ? List.of() : List.copyOf(worlds);
        int count = safeWorlds.size();
        double total = availableShareMs();
        double[] weights = new double[count];
        double weightSum = 0.0;
        for (int index = 0; index < count; index++) {
            Double configured = activityWeights == null ? null : activityWeights.get(safeWorlds.get(index));
            double weight = configured != null && Double.isFinite(configured) && configured > 0.0
                ? configured : 0.0;
            weights[index] = weight;
            weightSum += weight;
        }
        if (weightSum <= 0.0) {
            for (int index = 0; index < count; index++) {
                weights[index] = 1.0;
            }
            weightSum = count;
        }
        List<ShareTable.ShareRow> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String world = safeWorlds.get(index);
            double worldShare = count == 0 ? 0.0
                : fairWorldShare(total * weights[index] / weightSum);
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
        dualOverruns.computeIfAbsent(dualKey(row.worldId(), row.shareClass()),
            key -> new LongAdder()).increment();
        return new WouldDegrade(row.worldId(), row.shareClass(), true, levelFor(row.shareClass()));
    }

    /** Records what a degradation would have done. */
    public synchronized void recordWouldDegrade(OverrunRecord record) {
        if (records.size() >= RECORD_LIMIT) {
            OverrunRecord evicted = records.remove(0);
            recordTokens.remove(evicted);
        }
        records.add(record);
    }

    /** The stable internal identity of one trigger record; no readout key is added. */
    public synchronized long tokenOf(OverrunRecord record) {
        Long token = recordTokens.get(record);
        return token == null ? -1L : token;
    }

    /** True while the token still names a stored trigger record. */
    public synchronized boolean hasToken(long token) {
        return token > 0L && recordTokens.containsValue(token);
    }

    /** Drops the internal token table with the rest of the readings. */
    public synchronized void clearRecordTokens() {
        recordTokens.clear();
        actionExecutedFlag = false;
    }

    /** Marks the one record the token names, not every record of a tick or level. */
    public synchronized boolean markTokenExecuted(long token) {
        if (token <= 0L) {
            return false;
        }
        for (int index = records.size() - 1; index >= 0; index--) {
            OverrunRecord record = records.get(index);
            Long recordToken = recordTokens.get(record);
            if (recordToken != null && recordToken == token && !record.actionExecuted()) {
                records.set(index, record.withActionExecuted(true));
                actionExecutedFlag = true;
                return true;
            }
        }
        return false;
    }

    /** Turns an overrun row into the record shape without executing it. */
    public synchronized OverrunRecord record(ShareTable.ShareRow row, String siteId,
                                             long tickIndex) {
        WouldDegrade verdict = noteOverrun(row, siteId);
        if (verdict == null) {
            return null;
        }
        OverrunRecord record = new OverrunRecord(tickIndex, row.worldId(), row.shareClass(), siteId,
            classOverrunCount(row.shareClass()), worldOverrunCount(row.worldId()),
            verdict.level(), false);
        if (recordTokens.size() > RECORD_LIMIT * 2) {
            recordTokens.keySet().retainAll(records);
        }
        recordTokens.put(record, nextRecordToken++);
        return record;
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
        if (!Double.isFinite(millis) || millis < 0.0) {
            throw new IllegalArgumentException("a reserve draw must be a finite non-negative amount");
        }
        reserveUsed += millis;
        reserveUsedByPurpose.merge(purpose, millis, Double::sum);
    }

    /** Counts one world's world-by-class overrun; the two single-dimension counters stay as they
     * were and the composite row is the third observation a dual overrun needs. */
    public long dualOverrunCount(String worldId, ShareClass shareClass) {
        LongAdder counter = dualOverruns.get(dualKey(worldId, shareClass));
        return counter == null ? 0L : counter.sum();
    }

    public long dualOverrunTotal() {
        long total = 0L;
        for (LongAdder counter : dualOverruns.values()) {
            total += counter.sum();
        }
        return total;
    }

    private static String dualKey(String worldId, ShareClass shareClass) {
        return (worldId == null ? "" : worldId) + "|" + (shareClass == null ? "" : shareClass.key());
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

    /** Returns the per-world overrun state of a world that is unloading; the class totals stay,
     * because they are process-wide. */
    public synchronized void forgetWorld(String worldId) {
        worldOverruns.remove(worldId);
        String prefix = (worldId == null ? "" : worldId) + "|";
        dualOverruns.keySet().removeIf(key -> key.startsWith(prefix));
        hungerStreaks.remove(worldId);
        records.removeIf(record -> record.worldId().equals(worldId));
    }

    /** Observes one world's share for one tick. A share resets the streak; the first tick that
     * reaches the threshold publishes one starvation event and marks the forced yielding once. */
    public synchronized StarvationEvent observeWorldShare(String worldId, boolean hasShare,
                                                         long tickIndex, int threshold) {
        String key = worldId == null ? "" : worldId;
        if (hasShare) {
            hungerStreaks.remove(key);
            return null;
        }
        int wanted = Math.max(1, threshold);
        int missed = hungerStreaks.merge(key, 1, Integer::sum);
        if (missed == wanted) {
            hungerEvents.increment();
            return new StarvationEvent(key, tickIndex, missed, wanted, true);
        }
        return null;
    }

    public synchronized int hungerStreak(String worldId) {
        Integer missed = hungerStreaks.get(worldId == null ? "" : worldId);
        return missed == null ? 0 : missed;
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
        return actionExecutedFlag;
    }

    /** One starvation observation: the first tick a world reaches the missed-share threshold. The
     * forced-yield flag is the one turn the next batch would grant; this batch only records it. */
    public record StarvationEvent(String worldId, long tickIndex, int missedTicks, int threshold,
                                  boolean forcedYield) {
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
