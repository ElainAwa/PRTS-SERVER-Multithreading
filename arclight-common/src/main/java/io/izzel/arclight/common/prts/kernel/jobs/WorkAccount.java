/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The work account of one execution window: every offered business unit is one identity, a unit has
 * at most one seat, at most one pending sub-bucket and at most one terminal closure. The account is
 * the O_input side of the cohort equation, so a reader can always answer how many units were offered,
 * how many were seated, how many are still pending in which sub-bucket and how many closed with which
 * outcome. Mutations are serialized and every read returns a copy.
 *
 * <p>A unit is offered once per window. A repeated offer of the same identity is a no-op, and a
 * closure is final: a closed unit cannot be re-seated, moved to another bucket or closed again. The
 * account is safe across a restart when its {@link #snapshot()} is handed back to {@link #restore}.
 */
public final class WorkAccount {

    /** The terminal answer of one unit. The five members are mutually exclusive. */
    public enum Outcome {
        DONE,
        CANCELLED_NO_EFFECT,
        FAULT,
        PARTIAL_FAULT,
        REJECTED_BEFORE_EFFECT
    }

    /** The pending sub-buckets of one unit. Exactly one bucket applies while a unit is open. */
    public enum Bucket {
        NOT_READY,
        READY,
        RUNNING,
        WAIT_RESULT,
        PUBLISH_PENDING
    }

    /**
     * The identity of one offered unit: the node key, and the logical update ordinal when the job
     * names one. Two ticks of the same node are two units, while a job that names no ordinal keeps
     * exactly the identity it always had.
     */
    public static String unitId(String key, long logicalUpdateOrdinal) {
        if (key == null) {
            return "";
        }
        return logicalUpdateOrdinal > 0L ? key + "#" + logicalUpdateOrdinal : key;
    }

    /** One offered business unit: its stable identity and the world/domain it belongs to. */
    public record Unit(String unitId, String key, String worldId, String domainId) {

        public Unit {
            unitId = unitId == null ? "" : unitId;
            key = key == null ? "" : key;
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
        }
    }

    /** The plan position one unit was seated at. */
    public record Seat(long planSequence, long tickIndex, int position) {
    }

    /** The terminal outcome of one unit and the code that explains it. */
    public record Closure(Outcome outcome, String code) {

        public Closure {
            code = code == null ? "" : code;
        }
    }

    /** A read-only view of one unit, including its current sub-bucket or terminal closure. */
    public record UnitView(String unitId, String key, String worldId, String domainId, Seat seat,
                           Bucket bucket, Outcome outcome, String code, double wallMs, double cpuMs) {

        public UnitView {
            code = code == null ? "" : code;
        }
    }

    /** A read-only snapshot of the whole account, in offer order. */
    public record Snapshot(long offered, long seated, long pending, long closed,
                           Map<Bucket, Long> pendingByBucket, long done, long cancelledNoEffect,
                           long fault, long partialFault, long rejectedBeforeEffect,
                           double wallMs, double cpuMs, boolean settled, List<UnitView> units) {

        public Snapshot {
            pendingByBucket = immutableBuckets(pendingByBucket);
            units = List.copyOf(units == null ? List.of() : units);
        }
    }

    /** The answer of the conservation check: the cohort equation and its sub-bucket decomposition. */
    public record Conservation(boolean balanced, long offered, long seated, long pending, long closed,
                               Map<Bucket, Long> pendingByBucket, long done, long cancelledNoEffect,
                               long fault, long partialFault, long rejectedBeforeEffect,
                               boolean bucketsExclusive, boolean seatsConsistent, String note) {

        public Conservation {
            pendingByBucket = immutableBuckets(pendingByBucket);
            note = note == null ? "" : note;
        }

        /** Whether every counted unit is either pending in exactly one bucket or closed once. */
        public boolean ok() {
            return balanced;
        }
    }

    private static final class Entry {

        private final Unit unit;
        private final long sequence;
        private Seat seat;
        private Bucket bucket = Bucket.NOT_READY;
        private Closure closure;
        private double wallMs;
        private double cpuMs;

        private Entry(Unit unit, long sequence) {
            this.unit = unit;
            this.sequence = sequence;
        }
    }

    private final Map<String, Entry> units = new LinkedHashMap<>();
    private long sequence;
    private boolean settled;

    /** Offers one unit. Returns true only when this call registered a new identity. */
    public synchronized boolean offer(Unit unit) {
        if (unit == null || unit.unitId().isEmpty() || units.containsKey(unit.unitId())) {
            return false;
        }
        units.put(unit.unitId(), new Entry(unit, sequence++));
        settled = false;
        return true;
    }

    /** Offers one unit by its identity fields; null fields are normalized to empty strings. */
    public synchronized boolean offer(String unitId, String key, String worldId, String domainId) {
        return offer(new Unit(unitId, key, worldId, domainId));
    }

    /** The offered unit of one identity, or null when none was offered. */
    public synchronized Unit unit(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? null : entry.unit;
    }

    /** Number of units offered in this window. */
    public synchronized long offered() {
        return units.size();
    }

    /** Number of units that received a seat. */
    public synchronized long seated() {
        long count = 0L;
        for (Entry entry : units.values()) {
            if (entry.seat != null) {
                count++;
            }
        }
        return count;
    }

    /** Number of units that reached a terminal closure. */
    public synchronized long closed() {
        long count = 0L;
        for (Entry entry : units.values()) {
            if (entry.closure != null) {
                count++;
            }
        }
        return count;
    }

    /** Number of open units: every offered unit that has not closed. */
    public synchronized long pending() {
        return units.size() - closed();
    }

    /** Every offered unit, in offer order. */
    public synchronized Map<String, Unit> offeredUnits() {
        Map<String, Unit> copy = new LinkedHashMap<>();
        for (Entry entry : units.values()) {
            copy.put(entry.unit.unitId(), entry.unit);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Every seat, in offer order. */
    public synchronized Map<String, Seat> seats() {
        Map<String, Seat> copy = new LinkedHashMap<>();
        for (Entry entry : units.values()) {
            if (entry.seat != null) {
                copy.put(entry.unit.unitId(), entry.seat);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Every terminal closure, in offer order. */
    public synchronized Map<String, Closure> closures() {
        Map<String, Closure> copy = new LinkedHashMap<>();
        for (Entry entry : units.values()) {
            if (entry.closure != null) {
                copy.put(entry.unit.unitId(), entry.closure);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** The seat of one unit, or null when it was not seated or is unknown. */
    public synchronized Seat seat(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? null : entry.seat;
    }

    /** The terminal closure of one unit, or null while it is open or unknown. */
    public synchronized Closure closure(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? null : entry.closure;
    }

    /** The current pending sub-bucket of one unit, or null when it is closed or unknown. */
    public synchronized Bucket bucket(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? null : entry.bucket;
    }

    /** Seats one open unit. A repeated identical seat is accepted; a different seat is refused. */
    public synchronized boolean seat(String unitId, long planSequence, long tickIndex, int position) {
        return seat(unitId, new Seat(planSequence, tickIndex, position));
    }

    /** Seats one open unit at an existing seat value. */
    public synchronized boolean seat(String unitId, Seat seat) {
        if (unitId == null || seat == null) {
            return false;
        }
        Entry entry = units.get(unitId);
        if (entry == null || entry.closure != null) {
            return false;
        }
        if (entry.seat != null) {
            return entry.seat.equals(seat);
        }
        entry.seat = seat;
        return true;
    }

    /** Moves one open unit to a pending sub-bucket; a different bucket replaces the old one. */
    public synchronized boolean bucket(String unitId, Bucket bucket) {
        if (unitId == null || bucket == null) {
            return false;
        }
        Entry entry = units.get(unitId);
        if (entry == null || entry.closure != null) {
            return false;
        }
        entry.bucket = bucket;
        return true;
    }

    /** Moves one open unit to the not-ready sub-bucket. */
    public synchronized boolean markNotReady(String unitId) {
        return bucket(unitId, Bucket.NOT_READY);
    }

    /** Moves one open unit to the ready sub-bucket. */
    public synchronized boolean markReady(String unitId) {
        return bucket(unitId, Bucket.READY);
    }

    /** Moves one open unit to the running sub-bucket. */
    public synchronized boolean markRunning(String unitId) {
        return bucket(unitId, Bucket.RUNNING);
    }

    /** Moves one open unit to the wait-result sub-bucket. */
    public synchronized boolean markWaitResult(String unitId) {
        return bucket(unitId, Bucket.WAIT_RESULT);
    }

    /** Moves one open unit to the publish-pending sub-bucket. */
    public synchronized boolean markPublishPending(String unitId) {
        return bucket(unitId, Bucket.PUBLISH_PENDING);
    }

    /** Closes one open unit with its outcome and code. The first closure is final. */
    public synchronized boolean close(String unitId, Outcome outcome, String code) {
        if (unitId == null || outcome == null) {
            return false;
        }
        Entry entry = units.get(unitId);
        if (entry == null || entry.closure != null) {
            return false;
        }
        entry.closure = new Closure(outcome, code);
        entry.bucket = null;
        settled = allClosed();
        return true;
    }

    /** Closes one open unit with an empty code. */
    public synchronized boolean close(String unitId, Outcome outcome) {
        return close(unitId, outcome, "");
    }

    /** Adds measured wall and cpu milliseconds to one unit; negative or non-finite values add zero. */
    public synchronized boolean meter(String unitId, double wallMs, double cpuMs) {
        Entry entry = entry(unitId);
        if (entry == null) {
            return false;
        }
        entry.wallMs += nonNegative(wallMs);
        entry.cpuMs += nonNegative(cpuMs);
        return true;
    }

    /** The accumulated wall milliseconds of one unit, or zero when unknown. */
    public synchronized double wallMs(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? 0.0 : entry.wallMs;
    }

    /** The accumulated cpu milliseconds of one unit, or zero when unknown. */
    public synchronized double cpuMs(String unitId) {
        Entry entry = entry(unitId);
        return entry == null ? 0.0 : entry.cpuMs;
    }

    /** Number of open units in one pending sub-bucket. */
    public synchronized long pendingCount(Bucket bucket) {
        if (bucket == null) {
            return 0L;
        }
        long count = 0L;
        for (Entry entry : units.values()) {
            if (entry.closure == null && entry.bucket == bucket) {
                count++;
            }
        }
        return count;
    }

    /** Number of units closed with one outcome. */
    public synchronized long closedCount(Outcome outcome) {
        if (outcome == null) {
            return 0L;
        }
        long count = 0L;
        for (Entry entry : units.values()) {
            if (entry.closure != null && entry.closure.outcome() == outcome) {
                count++;
            }
        }
        return count;
    }

    /** Marks the account settled when every offered unit is closed; returns whether it is settled. */
    public synchronized boolean settle() {
        settled = allClosed();
        return settled;
    }

    /** Whether every offered unit has reached a terminal closure. An empty account is settled. */
    public synchronized boolean settled() {
        return settled;
    }

    /** The conservation equation of the current account state. */
    public synchronized Conservation conservation() {
        long seated = 0L;
        long pending = 0L;
        long closed = 0L;
        long done = 0L;
        long cancelled = 0L;
        long fault = 0L;
        long partial = 0L;
        long rejected = 0L;
        boolean exclusive = true;
        boolean seatsConsistent = true;
        EnumMap<Bucket, Long> buckets = emptyBuckets();
        for (Entry entry : units.values()) {
            if (entry.seat != null) {
                seated++;
                if (entry.seat.planSequence() < 0L || entry.seat.tickIndex() < 0L
                    || entry.seat.position() < 0) {
                    seatsConsistent = false;
                }
            }
            if (entry.closure == null) {
                pending++;
                if (entry.bucket == null) {
                    exclusive = false;
                } else {
                    buckets.merge(entry.bucket, 1L, Long::sum);
                }
                continue;
            }
            closed++;
            if (entry.bucket != null) {
                exclusive = false;
            }
            switch (entry.closure.outcome()) {
                case DONE -> done++;
                case CANCELLED_NO_EFFECT -> cancelled++;
                case FAULT -> fault++;
                case PARTIAL_FAULT -> partial++;
                case REJECTED_BEFORE_EFFECT -> rejected++;
                default -> exclusive = false;
            }
        }
        long bucketed = 0L;
        for (long value : buckets.values()) {
            bucketed += value;
        }
        boolean balanced = pending + closed == units.size()
            && bucketed == pending
            && done + cancelled + fault + partial + rejected == closed
            && exclusive
            && seatsConsistent;
        return new Conservation(balanced, units.size(), seated, pending, closed, buckets, done,
            cancelled, fault, partial, rejected, exclusive, seatsConsistent,
            balanced ? "" : "the pending sub-buckets do not add up to the open units");
    }

    /** Copies the current account into a read-only snapshot. */
    public synchronized Snapshot snapshot() {
        long seated = 0L;
        long closed = 0L;
        long done = 0L;
        long cancelled = 0L;
        long fault = 0L;
        long partial = 0L;
        long rejected = 0L;
        double wall = 0.0;
        double cpu = 0.0;
        EnumMap<Bucket, Long> buckets = emptyBuckets();
        List<UnitView> views = new ArrayList<>(units.size());
        for (Entry entry : units.values()) {
            if (entry.seat != null) {
                seated++;
            }
            if (entry.closure == null) {
                buckets.merge(entry.bucket, 1L, Long::sum);
            } else {
                closed++;
                switch (entry.closure.outcome()) {
                    case DONE -> done++;
                    case CANCELLED_NO_EFFECT -> cancelled++;
                    case FAULT -> fault++;
                    case PARTIAL_FAULT -> partial++;
                    case REJECTED_BEFORE_EFFECT -> rejected++;
                }
            }
            wall += entry.wallMs;
            cpu += entry.cpuMs;
            views.add(new UnitView(entry.unit.unitId(), entry.unit.key(), entry.unit.worldId(),
                entry.unit.domainId(), entry.seat, entry.bucket,
                entry.closure == null ? null : entry.closure.outcome(),
                entry.closure == null ? "" : entry.closure.code(), entry.wallMs, entry.cpuMs));
        }
        return new Snapshot(units.size(), seated, units.size() - closed, closed, buckets, done,
            cancelled, fault, partial, rejected, wall, cpu, settled, views);
    }

    /** Rebuilds an account from a snapshot; a null snapshot answers an empty account. */
    public static WorkAccount restore(Snapshot snapshot) {
        WorkAccount account = new WorkAccount();
        if (snapshot == null) {
            return account;
        }
        for (UnitView view : snapshot.units()) {
            account.offer(view.unitId(), view.key(), view.worldId(), view.domainId());
            if (view.seat() != null) {
                account.seat(view.unitId(), view.seat());
            }
            if (view.outcome() == null && view.bucket() != null) {
                account.bucket(view.unitId(), view.bucket());
            }
            account.meter(view.unitId(), view.wallMs(), view.cpuMs());
            if (view.outcome() != null) {
                account.close(view.unitId(), view.outcome(), view.code());
            }
        }
        if (snapshot.settled()) {
            account.settle();
        }
        return account;
    }

    private Entry entry(String unitId) {
        return unitId == null ? null : units.get(unitId);
    }

    private boolean allClosed() {
        for (Entry entry : units.values()) {
            if (entry.closure == null) {
                return false;
            }
        }
        return true;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    private static EnumMap<Bucket, Long> emptyBuckets() {
        EnumMap<Bucket, Long> buckets = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) {
            buckets.put(bucket, 0L);
        }
        return buckets;
    }

    private static Map<Bucket, Long> immutableBuckets(Map<Bucket, Long> source) {
        EnumMap<Bucket, Long> copy = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) {
            Long value = source == null ? null : source.get(bucket);
            copy.put(bucket, value == null ? 0L : value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
