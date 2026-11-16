/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The per-tick, per-identity ledger of the rows a tick offers: every observed row keeps its full
 * identity and eligibility reason, every identity the tick promised is reconciled against the
 * observed set, and a row a declared capacity cannot hold is retained in a refusal list instead of
 * being dropped. The ledger holds plain values only and the tick thread owns one instance per tick.
 */
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
    public enum State { LOADED, ELIGIBLE, MISSING, EXTRA, OVER_CAPACITY }

    /** One retained row; only a missing row has no reason. */
    public record Row(EntityRowIdentity identity, EntityEligibility.Reason reason, State state) {

        public Row {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(state, "state");
            if (state != State.MISSING) {
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
    private long duplicateObservations;
    private long idReuses;
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
        IdKey idKey = new IdKey(identity.worldId(), identity.worldEpoch(), identity.entityId());
        EntityRowIdentity.ObjectKey first = observedIds.putIfAbsent(idKey, key);
        if (first != null && !first.equals(key)) {
            idReuses++;
        }
        State state = EntityEligibility.admitted(reason) ? State.ELIGIBLE : State.LOADED;
        Row row = new Row(identity, reason, state);
        if (rows.size() < capacity) {
            rows.put(key, row);
            return true;
        }
        overflow.add(new Row(identity, reason, State.OVER_CAPACITY));
        return false;
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
