/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.support.EntityEligibility;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The identity ledger of one tick: the rows the work source offered, the reason each of them was
 * admitted or refused, and the reconciliation of the identities the tick promised against the ones
 * it observed. This slice tracks loaded, eligible, missing and extra; the later states a due source
 * and a takeover pass produce are not declared before their producers exist.
 */
public final class EntityDueLedger {

    /** The tracked lifecycle state of one ledger row. */
    public enum State { LOADED, ELIGIBLE, DUE, ADMITTED, REFUSED, MISSING, EXTRA, OVER_CAPACITY }

    /** One retained row; a missing row and a capacity refusal carry no eligibility reason. */
    public record Row(EntityRowIdentity identity, EntityEligibility.Reason reason, State state) {

        public Row {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(state, "state");
            if (state != State.MISSING && state != State.OVER_CAPACITY) {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** The numeric id scoped to one world incarnation; an id alone never spans worlds. */
    private record IdKey(String worldId, long worldEpoch, int entityId) {
    }

    private final long tick;
    private final int capacity;
    private final Map<EntityRowIdentity.ObjectKey, Row> rows = new LinkedHashMap<>();
    private final List<Row> overflow = new ArrayList<>();
    private final Map<EntityRowIdentity.ObjectKey, EntityRowIdentity> expected =
        new LinkedHashMap<>();
    private final Map<IdKey, EntityRowIdentity.ObjectKey> expectedIds = new HashMap<>();
    private final Map<IdKey, EntityRowIdentity.ObjectKey> observedIds = new HashMap<>();
    private final List<Row> missing = new ArrayList<>();
    private final List<Row> extra = new ArrayList<>();
    private final Map<String, Integer> loadedByWorld = new LinkedHashMap<>();
    private long duplicateObservations;
    private long idReuses;
    private long dueCount;
    private long admittedCount;
    private long passengerDue;
    private long nonPassengerDue;
    private long removedDue;
    private long overCapacityDue;
    private boolean closed;

    private EntityDueLedger(long tick, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("a ledger needs a positive capacity");
        }
        this.tick = tick;
        this.capacity = capacity;
    }

    /** Opens the ledger of one tick; the caller owns it until the tick closes. */
    public static EntityDueLedger open(long tick, int capacity) {
        return new EntityDueLedger(tick, capacity);
    }

    /** The tick this ledger belongs to; negative when the caller did not name one. */
    public long tick() {
        return tick;
    }

    /** The number of rows the tracked table holds before a row is retained as a refusal. */
    public int capacity() {
        return capacity;
    }

    /**
     * Declares one identity the tick promised to serve; the first identity declared for a numeric
     * id in one world incarnation owns that id for the reconciliation.
     */
    public boolean expect(EntityRowIdentity identity) {
        requireOpen("expect");
        Objects.requireNonNull(identity, "identity");
        EntityRowIdentity.ObjectKey key = identity.objectKey();
        if (expected.putIfAbsent(key, identity) != null) {
            return false;
        }
        expectedIds.putIfAbsent(new IdKey(identity.worldId(), identity.worldEpoch(),
            identity.entityId()), key);
        return true;
    }

    /**
     * Records one observed row. Returns whether the row entered the tracked table; a row beyond the
     * declared capacity is retained in the refusal list, so no row this method saw is dropped.
     */
    public boolean observe(EntityRowIdentity identity, EntityEligibility.Reason reason) {
        requireOpen("observe");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reason, "reason");
        EntityRowIdentity.ObjectKey key = identity.objectKey();
        if (rows.containsKey(key) || retained(key)) {
            duplicateObservations++;
            return false;
        }
        noteId(identity);
        State state = EntityEligibility.admitted(reason) ? State.ELIGIBLE : State.LOADED;
        Row row = new Row(identity, reason, state);
        if (rows.size() < capacity) {
            rows.put(key, row);
            return true;
        }
        overflow.add(new Row(identity, reason, State.OVER_CAPACITY));
        return false;
    }

    /** Books one level's loaded entity total; the due rows of that level are booked apart. */
    public void noteLoaded(String worldId, int loaded) {
        requireOpen("note loaded");
        Objects.requireNonNull(worldId, "worldId");
        if (loaded < 0) {
            throw new IllegalArgumentException("a loaded total cannot be negative");
        }
        loadedByWorld.put(worldId, loaded);
    }

    /**
     * Books one identity the host entity tick list carried at the start of the tick, with its
     * passenger split and the reason the census reached. A row the host will skip because it is
     * already removed is retained as a refusal; a row beyond the declared capacity is retained as
     * an explicit capacity refusal, so no due identity is dropped.
     *
     * @return whether the row entered the tracked table
     */
    public boolean due(EntityRowIdentity identity, boolean passenger, boolean removed,
                       EntityEligibility.Reason reason) {
        requireOpen("book due");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reason, "reason");
        EntityRowIdentity.ObjectKey key = identity.objectKey();
        if (rows.containsKey(key) || retained(key)) {
            duplicateObservations++;
            return false;
        }
        noteId(identity);
        dueCount++;
        if (passenger) {
            passengerDue++;
        } else {
            nonPassengerDue++;
        }
        if (removed) {
            rows.put(key, new Row(identity, EntityEligibility.Reason.LIFECYCLE, State.REFUSED));
            removedDue++;
            return true;
        }
        if (rows.size() < capacity) {
            rows.put(key, new Row(identity, reason, State.DUE));
            return true;
        }
        overflow.add(new Row(identity, null, State.OVER_CAPACITY));
        overCapacityDue++;
        return false;
    }

    /** Marks one due row as admitted to the tick's work plan; only a tracked due row advances. */
    public boolean admit(EntityRowIdentity identity) {
        requireOpen("admit");
        Objects.requireNonNull(identity, "identity");
        EntityRowIdentity.ObjectKey key = identity.objectKey();
        Row row = rows.get(key);
        if (row == null || row.state() != State.DUE) {
            return false;
        }
        rows.put(key, new Row(identity, row.reason(), State.ADMITTED));
        admittedCount++;
        return true;
    }

    /** The loaded entity total of one level, or zero when the caller did not name one. */
    public int loaded(String worldId) {
        Integer loaded = loadedByWorld.get(worldId);
        return loaded == null ? 0 : loaded;
    }

    /** The loaded entity total over every level this ledger booked. */
    public int loaded() {
        int total = 0;
        for (int loaded : loadedByWorld.values()) {
            total += loaded;
        }
        return total;
    }

    /** The identities the host tick list carried at the start of the tick. */
    public long dueCount() {
        return dueCount;
    }

    /** The due identities that reached the tick's work plan. */
    public long admittedCount() {
        return admittedCount;
    }

    /** The due identities that never reached the work plan: removed, or beyond the bound. */
    public long refusedDueCount() {
        return dueCount - admittedCount;
    }

    /** The due identities whose host tick runs as a passenger ride tick. */
    public long passengerDueCount() {
        return passengerDue;
    }

    /** The due identities whose host tick runs as a non-passenger tick. */
    public long nonPassengerDueCount() {
        return nonPassengerDue;
    }

    /** The due identities the host would already have skipped because they were removed. */
    public long removedDueCount() {
        return removedDue;
    }

    /** The due identities the declared memory bound kept out of the work plan. */
    public long overCapacityDueCount() {
        return overCapacityDue;
    }

    /** Whether every due identity ended admitted or explicitly refused. */
    public boolean dueAccounted() {
        return dueCount == admittedCount + removedDue + overCapacityDue;
    }

    /**
     * Closes the tick: a promised identity the tick did not observe is missing, and an observed row
     * that occupies the numeric id of a different promised identity is extra. Returns whether this
     * call closed the ledger.
     */
    public boolean close() {
        if (closed) {
            return false;
        }
        closed = true;
        Set<EntityRowIdentity.ObjectKey> observedKeys = new HashSet<>(rows.keySet());
        for (Row retained : overflow) {
            observedKeys.add(retained.identity().objectKey());
        }
        for (Map.Entry<EntityRowIdentity.ObjectKey, EntityRowIdentity> entry : expected.entrySet()) {
            if (!observedKeys.contains(entry.getKey())) {
                missing.add(new Row(entry.getValue(), null, State.MISSING));
            }
        }
        for (EntityRowIdentity.ObjectKey key : new ArrayList<>(rows.keySet())) {
            Row row = rows.get(key);
            if (isExtra(row.identity())) {
                Row marked = new Row(row.identity(), row.reason(), State.EXTRA);
                rows.put(key, marked);
                extra.add(marked);
            }
        }
        for (Row retained : overflow) {
            if (isExtra(retained.identity())) {
                extra.add(new Row(retained.identity(), retained.reason(), State.EXTRA));
            }
        }
        return true;
    }

    /** Whether the ledger of this tick has closed. */
    public boolean closed() {
        return closed;
    }

    /** The tracked row of one identity, or null when the ledger did not retain it. */
    public Row row(EntityRowIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Row row = rows.get(identity.objectKey());
        if (row != null) {
            return row;
        }
        for (Row retained : overflow) {
            if (retained.identity().objectKey().equals(identity.objectKey())) {
                return retained;
            }
        }
        return null;
    }

    /** The tracked rows in observation order. */
    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    /** The rows retained only as capacity refusals, in observation order. */
    public List<Row> overflow() {
        return List.copyOf(overflow);
    }

    /** The promised identities the tick did not observe. */
    public List<Row> missing() {
        return List.copyOf(missing);
    }

    /** The observed rows that occupied the numeric id of a different promised identity. */
    public List<Row> extra() {
        return List.copyOf(extra);
    }

    /** The tracked rows. */
    public int size() {
        return rows.size();
    }

    /** Every row this ledger retained: the tracked rows plus the capacity refusals. */
    public int retained() {
        return rows.size() + overflow.size();
    }

    /** The observed rows the predicate admitted. */
    public int eligible() {
        int admitted = 0;
        for (Row row : rows.values()) {
            if (row.state() == State.ELIGIBLE) {
                admitted++;
            }
        }
        return admitted;
    }

    /** The rows retained beyond the declared capacity. */
    public int refusedOverCapacity() {
        return overflow.size();
    }

    /** The promises the tick did not keep. */
    public int missingCount() {
        return missing.size();
    }

    /** The rows that occupied a promised numeric id under another identity. */
    public int extraCount() {
        return extra.size();
    }

    /** Observed rows this ledger saw twice; a row is recorded once. */
    public long duplicateObservations() {
        return duplicateObservations;
    }

    /** Numeric ids this ledger observed under more than one object lifecycle. */
    public long idReuses() {
        return idReuses;
    }

    private void noteId(EntityRowIdentity identity) {
        IdKey idKey = new IdKey(identity.worldId(), identity.worldEpoch(), identity.entityId());
        EntityRowIdentity.ObjectKey first = observedIds.putIfAbsent(idKey, identity.objectKey());
        if (first != null && !first.equals(identity.objectKey())) {
            idReuses++;
        }
    }

    private boolean retained(EntityRowIdentity.ObjectKey key) {
        for (Row row : overflow) {
            if (row.identity().objectKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExtra(EntityRowIdentity identity) {
        IdKey idKey = new IdKey(identity.worldId(), identity.worldEpoch(), identity.entityId());
        EntityRowIdentity.ObjectKey promised = expectedIds.get(idKey);
        return promised != null && !promised.equals(identity.objectKey());
    }

    private void requireOpen(String operation) {
        if (closed) {
            throw new IllegalStateException("a closed ledger cannot " + operation);
        }
    }
}
