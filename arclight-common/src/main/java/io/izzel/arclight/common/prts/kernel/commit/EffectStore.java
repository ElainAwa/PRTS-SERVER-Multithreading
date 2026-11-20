/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/** The typed effect records one commit line may point at: one immutable record per line that
 * declares one, bounded by the bytes and by the record count the store was built with, and never
 * evicted while a line still holds it.
 *
 * <p>Room is taken before anything is stored: a reservation charges the record it will hold and
 * freezes what it charged for, so a second record cannot be admitted into room the first one already
 * owns and a reservation cannot be filled with another record of the same length. A store built with
 * no room at all is refused rather than accepted as an empty and always-failing table.
 *
 * <p>Room alone may also be reserved, for a line whose outcome is not known until the side effect
 * has run: that reservation promises how many records and how many bytes the terminal record will
 * take, and the record is written inside that promise without asking for room again. A terminal
 * record that outgrows its promise is not written and not thrown away: it is counted as an
 * integrity fault and stays readable, and the store answers every later reservation with a refusal.
 *
 * <p>Room is handed out only while the store is whole: once a terminal record outgrew its promise
 * the store is halted, and every later reservation is refused on every path, the freeze path
 * included.
 *
 * <p>Every method that reads the ledgers and then moves them runs under the store's monitor, so a
 * handle is checked and consumed in one step: of two threads ending the same room-only handle one
 * terminal is admitted and the other is answered as a handle this store does not hold, and no ledger
 * is moved twice. What a caller reads of those ledgers is read under the same monitor.
 *
 * <p>The store is in memory only: nothing here writes a file, so nothing here survives a restart.
 * A reference that cannot be resolved is answered as missing and never as an empty record. */
public final class EffectStore {

    /** One effect a producer declares: the operation, the outcome and the rows describing what was
     * published. The rows are normalized when the record is stored, not when they arrive. */
    public record Effect(long effectRef, String operationKind, String outcome, List<String> rows) {

        public Effect {
            operationKind = operationKind == null ? "" : operationKind;
            outcome = outcome == null ? "" : outcome;
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    /** A stored record: the normalized rows, the field names they declare, the digest of the rows
     * read as bytes, the room it takes and the order it was stored in. */
    public record Recorded(long effectRef, String operationKind, String outcome, List<String> declared,
                           List<String> content, String contentDigest, int bytes, long sequence) {

        public Recorded {
            declared = List.copyOf(declared);
            content = List.copyOf(content);
        }
    }

    /** The room one effect was charged for, and the record that room was charged for: the normalized
     * content, the operation and the outcome are frozen here, so the reservation is a statement of
     * what will be stored and not only of how much room it takes. */
    public record Slot(long ticket, long effectRef, int bytes, String operationKind, String outcome,
                       String contentDigest, List<String> content) {

        public Slot {
            content = List.copyOf(content == null ? List.of() : content);
        }
    }

    /** What one reservation or one store did. */
    public record Result(boolean admitted, RejectCode code, Slot slot, Recorded record) {
    }

    /** A reservation of room alone: the reference the terminal record will carry, the records and
     * bytes it may take, and the store that handed it out. The record itself is not known yet, which
     * is why this is a second protocol and not a relaxation of the first one. */
    public record CapacitySlot(long ticket, long effectRef, int maxRecords, int maxBytes, long storeId) {
    }

    /** What one room-only reservation did. */
    public record CapacityResult(boolean admitted, RejectCode code, CapacitySlot slot, Recorded record) {
    }

    private static final int HEADER_BYTES = 48;
    private static final int ROW_BYTES = 4;
    private static final int FIELD_BYTES = 4;
    private static final int NAME_BYTES = 4;
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private static final AtomicLong STORE_IDS = new AtomicLong(1L);

    private final long storeId = STORE_IDS.getAndIncrement();
    private final int capacityBytes;
    private final int capacityRecords;
    private final Map<Long, Recorded> records = new LinkedHashMap<>();
    private final Map<Long, Recorded> consumed = new LinkedHashMap<>();
    private final Map<Long, Slot> slots = new LinkedHashMap<>();
    private final Map<Long, CapacitySlot> capacitySlots = new LinkedHashMap<>();
    private final Map<Long, Integer> pins = new LinkedHashMap<>();
    private int usedBytes;
    private int reservedBytes;
    private int capacityReservedBytes;
    private int capacityReservedRecords;
    private long tickets;
    private long sequence;
    private long stored;
    private long refused;
    private long released;
    private long consumedRecords;
    private long committed;
    private long evicted;
    private long missing;
    private long evictionRefusals;
    private long capacityReserved;
    private long capacityFinalized;
    private long capacityReleased;
    private long foreignHandles;
    private long integrityFaults;
    private boolean halted;
    private String integrityReason = "";

    /** A store with no byte or no record room is not executable: it is refused here instead of
     * answering every reservation with a refusal while it looks like an empty and healthy table. */
    public EffectStore(int capacityBytes, int capacityRecords) {
        if (capacityBytes <= 0 || capacityRecords <= 0) {
            throw new IllegalArgumentException("an effect store needs room in bytes and in records: "
                + capacityBytes + " bytes, " + capacityRecords + " records");
        }
        this.capacityBytes = capacityBytes;
        this.capacityRecords = capacityRecords;
    }

    /** Charges the room one effect will take and freezes the record it charged for. Nothing is
     * stored yet; the slot is filled by {@link #commit} or given back by {@link #release}. */
    public synchronized Result reserve(Effect effect) {
        if (halted) {
            refused++;
            return new Result(false, RejectCode.QUEUE_CAP_EXCEEDED, null, null);
        }
        if (effect == null || effect.effectRef() <= 0L) {
            refused++;
            return new Result(false, RejectCode.COUNTER_MISSING, null, null);
        }
        List<String> content = normalize(effect.rows());
        long charge = chargedBytes(effect.operationKind(), effect.outcome(), content);
        if (records.containsKey(effect.effectRef()) || holdsTicketFor(effect.effectRef())
            || !fitsRecords(records.size(), slots.size() + capacityReservedRecords + 1L, capacityRecords)
            || !fitsBytes(usedBytes, reservedBytes + capacityReservedBytes, charge, capacityBytes)) {
            refused++;
            return new Result(false, RejectCode.QUEUE_CAP_EXCEEDED, null, null);
        }
        Slot slot = new Slot(++tickets, effect.effectRef(), (int) charge, effect.operationKind(),
            effect.outcome(), digestOf(content), content);
        slots.put(slot.ticket(), slot);
        reservedBytes += slot.bytes();
        return new Result(true, null, slot, null);
    }

    /** Stores the record one reservation was charged for. The frozen record and the one that arrives
     * are compared field by field, so a record that changed in between is refused and its room is
     * given back even when the two are the same length. */
    public synchronized Result commit(Slot slot, Effect effect) {
        Slot held = slot == null ? null : slots.get(slot.ticket());
        if (held == null || effect == null || held.effectRef() != effect.effectRef()) {
            refused++;
            return new Result(false, RejectCode.COUNTER_MISSING, null, null);
        }
        if (held != slot) {
            // The handle this store saved is the only one that may fill this slot: a slot of another
            // store carries the same ticket number and an equal value, and would take the room of a
            // reservation it never made.
            foreignHandles++;
            refused++;
            return new Result(false, RejectCode.COUNTER_MISSING, null, null);
        }
        List<String> content = normalize(effect.rows());
        if (!isTheReservedRecord(held, effect, content)) {
            release(held);
            refused++;
            return new Result(false, RejectCode.VERSION_MISMATCH, null, null);
        }
        slots.remove(held.ticket());
        reservedBytes -= held.bytes();
        Recorded record = new Recorded(effect.effectRef(), effect.operationKind(), effect.outcome(),
            declaredOf(content), content, held.contentDigest(), held.bytes(), ++sequence);
        records.put(record.effectRef(), record);
        pins.put(record.effectRef(), 1);
        usedBytes += held.bytes();
        stored++;
        committed++;
        return new Result(true, null, null, record);
    }

    private static boolean isTheReservedRecord(Slot held, Effect arriving, List<String> content) {
        return held.bytes() == chargedBytes(arriving.operationKind(), arriving.outcome(), content)
            && held.operationKind().equals(arriving.operationKind())
            && held.outcome().equals(arriving.outcome())
            && held.content().equals(content)
            && held.contentDigest().equals(digestOf(content));
    }

    /** Gives back a reservation that was not used. A ticket that is not held is a no-op, and a
     * handle another store handed out is refused instead of giving back this store's room. */
    public synchronized boolean release(Slot slot) {
        Slot held = slot == null ? null : slots.get(slot.ticket());
        if (held == null || held != slot) {
            if (slot != null) {
                foreignHandles++;
            }
            return false;
        }
        slots.remove(held.ticket());
        reservedBytes -= held.bytes();
        released++;
        return true;
    }

    /** Charges room for a record whose outcome is not known yet: the room covers the largest of the
     * terminal variants the caller declares, and the record is written later inside that promise. */
    public synchronized CapacityResult reserveCapacity(long effectRef, int maxRecords, int maxBytes) {
        if (halted) {
            refused++;
            return new CapacityResult(false, RejectCode.QUEUE_CAP_EXCEEDED, null, null);
        }
        if (effectRef <= 0L || maxRecords <= 0 || maxBytes <= 0) {
            refused++;
            return new CapacityResult(false, RejectCode.COUNTER_MISSING, null, null);
        }
        if (records.containsKey(effectRef) || holdsTicketFor(effectRef)
            || !fitsRecords(records.size(), slots.size() + capacityReservedRecords + (long) maxRecords,
                capacityRecords)
            || !fitsBytes(usedBytes, reservedBytes + capacityReservedBytes + (long) maxBytes, 0L,
                capacityBytes)) {
            refused++;
            return new CapacityResult(false, RejectCode.QUEUE_CAP_EXCEEDED, null, null);
        }
        CapacitySlot slot = new CapacitySlot(++tickets, effectRef, maxRecords, maxBytes, storeId);
        capacitySlots.put(slot.ticket(), slot);
        capacityReservedBytes += maxBytes;
        capacityReservedRecords += maxRecords;
        capacityReserved++;
        return new CapacityResult(true, null, slot, null);
    }

    /** Writes the terminal record inside the room that was reserved for it. The promise is the
     * bound: a record that outgrows it is not written, not billed and not dropped quietly. The handle
     * is looked up and consumed in one step, so one reservation ends exactly once. */
    public synchronized CapacityResult finalizeCapacity(CapacitySlot slot, Effect terminal) {
        CapacitySlot held = slot == null ? null : capacitySlots.get(slot.ticket());
        if (held == null || held != slot || slot.storeId() != storeId || terminal == null
            || terminal.effectRef() != held.effectRef()) {
            foreignHandles++;
            return new CapacityResult(false, RejectCode.COUNTER_MISSING, null, null);
        }
        List<String> content = normalize(terminal.rows());
        long charge = chargedBytes(terminal.operationKind(), terminal.outcome(), content);
        if (content.size() > held.maxRecords() || charge > held.maxBytes()) {
            // The prompt bound was wrong or the outcome grew past it. The room is not raised after
            // the side effect, and the audit is not thrown away: the item stays reserved and
            // readable while the store stops handing out room.
            integrityFaults++;
            halted = true;
            integrityReason = "reserved-bound-exceeded";
            return new CapacityResult(false, RejectCode.QUEUE_CAP_EXCEEDED, held, null);
        }
        capacitySlots.remove(held.ticket());
        capacityReservedBytes -= held.maxBytes();
        capacityReservedRecords -= held.maxRecords();
        Recorded record = new Recorded(terminal.effectRef(), terminal.operationKind(),
            terminal.outcome(), declaredOf(content), content, digestOf(content), (int) charge, ++sequence);
        records.put(record.effectRef(), record);
        pins.put(record.effectRef(), 1);
        usedBytes += record.bytes();
        stored++;
        committed++;
        capacityFinalized++;
        return new CapacityResult(true, null, null, record);
    }

    /** Gives back room that was reserved and not used. The handle is looked up and given back in
     * one step, so a give-back that races the terminal of the same handle loses to it. */
    public synchronized boolean releaseCapacity(CapacitySlot slot) {
        CapacitySlot held = slot == null ? null : capacitySlots.get(slot.ticket());
        if (held == null || held != slot || slot.storeId() != storeId) {
            foreignHandles++;
            return false;
        }
        capacitySlots.remove(held.ticket());
        capacityReservedBytes -= held.maxBytes();
        capacityReservedRecords -= held.maxRecords();
        capacityReleased++;
        return true;
    }

    /** Moves one published record out of the live room into the published archive: the record stays
     * readable for the line that points at it, while the bytes and record slot are free again. Only
     * a record the publication path consumed goes here; it is not an eviction of an audited line. */
    public synchronized boolean consume(long effectRef) {
        Recorded record = records.remove(effectRef);
        if (record == null) {
            missing++;
            return false;
        }
        pins.remove(effectRef);
        usedBytes -= record.bytes();
        consumed.put(effectRef, record);
        consumedRecords++;
        return true;
    }

    /** The stored record one reference names, or null and a missing count when there is none. */
    public synchronized Recorded record(long effectRef) {
        Recorded record = records.get(effectRef);
        if (record == null) {
            record = consumed.get(effectRef);
        }
        if (record == null) {
            missing++;
        }
        return record;
    }

    /** Adds one reference to a stored record. The line that points at a record holds one. */
    public synchronized boolean pin(long effectRef) {
        if (!records.containsKey(effectRef)) {
            missing++;
            return false;
        }
        pins.merge(effectRef, 1, Integer::sum);
        return true;
    }

    /** Drops one reference from a stored record. */
    public synchronized boolean unpin(long effectRef) {
        Integer held = pins.get(effectRef);
        if (held == null || held <= 0) {
            return false;
        }
        if (held == 1) {
            pins.remove(effectRef);
        } else {
            pins.put(effectRef, held - 1);
        }
        return true;
    }

    /** Drops a record and gives its room back. A record a line still holds is not evicted. */
    public synchronized boolean evict(long effectRef) {
        Recorded record = records.get(effectRef);
        if (record == null) {
            missing++;
            return false;
        }
        if (pins.getOrDefault(effectRef, 0) > 0) {
            evictionRefusals++;
            return false;
        }
        records.remove(effectRef);
        usedBytes -= record.bytes();
        evicted++;
        return true;
    }

    private boolean holdsTicketFor(long effectRef) {
        for (Slot slot : slots.values()) {
            if (slot.effectRef() == effectRef) {
                return true;
            }
        }
        for (CapacitySlot slot : capacitySlots.values()) {
            if (slot.effectRef() == effectRef) {
                return true;
            }
        }
        return false;
    }

    /** The room one record of these rows is charged for, as the reservation will charge it: the
     * caller that promises a bound computes it with the same rule the store bills with. */
    public static List<String> normalizedRows(List<String> rows) {
        return normalize(rows == null ? List.of() : rows);
    }

    /** The digest of these rows, computed from the rows themselves: a reader that is handed a stored
     * record recomputes it instead of trusting the digest that came along with it. */
    public static String contentDigest(List<String> rows) {
        return digestOf(normalize(rows == null ? List.of() : rows));
    }

    /** The field names these rows declare, computed from the rows themselves, in the order the store
     * writes them. */
    public static List<String> declaredRows(List<String> rows) {
        return declaredOf(normalize(rows == null ? List.of() : rows));
    }

    public static long chargeOf(String operationKind, String outcome, List<String> rows) {
        return chargedBytes(operationKind == null ? "" : operationKind,
            outcome == null ? "" : outcome, normalize(rows == null ? List.of() : rows));
    }

    private static List<String> normalize(List<String> rows) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String row : rows) {
            if (row == null) {
                continue;
            }
            String trimmed = row.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> declaredOf(List<String> content) {
        TreeSet<String> names = new TreeSet<>();
        for (String row : content) {
            int equals = row.indexOf('=');
            String name = (equals < 0 ? row : row.substring(0, equals)).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /** The room one record is charged for: the fixed header, every content row, the two variable
     * fields and the field names the record declares. A record is charged for what it carries, so an
     * operation or an outcome of any length is paid for like a row is. */
    private static long chargedBytes(String operationKind, String outcome, List<String> content) {
        long bytes = HEADER_BYTES;
        for (String row : content) {
            bytes += utf8(row) + ROW_BYTES;
        }
        bytes += utf8(operationKind) + FIELD_BYTES;
        bytes += utf8(outcome) + FIELD_BYTES;
        for (String name : declaredOf(content)) {
            bytes += utf8(name) + NAME_BYTES;
        }
        return bytes;
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Whether a charge fits the room that is left. The three numbers are compared step by step as
     * longs, so a charge larger than any capacity is answered "no room" instead of wrapping around
     * an int and looking small. */
    static boolean fitsBytes(long usedBytes, long reservedBytes, long charge, int capacityBytes) {
        if (usedBytes < 0L || reservedBytes < 0L || charge < 0L || usedBytes > capacityBytes) {
            return false;
        }
        long left = capacityBytes - usedBytes;
        return reservedBytes <= left && charge <= left - reservedBytes;
    }

    /** The same for the record bound: the records already stored and the reservations already handed
     * out are compared against the capacity without adding the three of them up first. */
    static boolean fitsRecords(long records, long reserved, int capacityRecords) {
        if (records < 0L || reserved < 0L || records > capacityRecords) {
            return false;
        }
        return reserved <= capacityRecords - records;
    }

    private static String digestOf(List<String> content) {
        long hash = OFFSET_BASIS;
        for (String row : content) {
            for (byte value : row.getBytes(StandardCharsets.UTF_8)) {
                hash ^= (value & 0xff);
                hash *= PRIME;
            }
            hash ^= '\n';
            hash *= PRIME;
        }
        return Long.toHexString(hash);
    }

    public int capacityBytes() {
        return capacityBytes;
    }

    public int capacityRecords() {
        return capacityRecords;
    }

    public synchronized int records() {
        return records.size();
    }

    public synchronized int bytes() {
        return usedBytes;
    }

    public synchronized int reservedBytes() {
        return reservedBytes;
    }

    public synchronized int reservedRecords() {
        return slots.size();
    }

    public synchronized int pins() {
        int held = 0;
        for (Integer count : pins.values()) {
            held += count;
        }
        return held;
    }

    public synchronized long tickets() {
        return tickets;
    }

    public synchronized long stored() {
        return stored;
    }

    public synchronized long committed() {
        return committed;
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long released() {
        return released;
    }

    public synchronized long consumedRecords() {
        return consumedRecords;
    }

    public synchronized long evicted() {
        return evicted;
    }

    public synchronized long missing() {
        return missing;
    }

    public synchronized long evictionRefusals() {
        return evictionRefusals;
    }

    /** The store's own identity. It is minted here and rides on every room-only handle. */
    public long storeId() {
        return storeId;
    }

    public synchronized int capacityReservedBytes() {
        return capacityReservedBytes;
    }

    public synchronized int capacityReservedRecords() {
        return capacityReservedRecords;
    }

    /** The room-only reservations handed out and neither filled nor given back. */
    public synchronized int capacitySlots() {
        return capacitySlots.size();
    }

    public synchronized long capacityReserved() {
        return capacityReserved;
    }

    public synchronized long capacityFinalized() {
        return capacityFinalized;
    }

    public synchronized long capacityReleased() {
        return capacityReleased;
    }

    /** Handles of another store, of an earlier lifecycle or of no reservation at all. */
    public synchronized long foreignHandles() {
        return foreignHandles;
    }

    public synchronized long integrityFaults() {
        return integrityFaults;
    }

    /** Whether a terminal record outgrew its promise: the store then hands out no more room. */
    public synchronized boolean halted() {
        return halted;
    }

    public synchronized String integrityReason() {
        return integrityReason;
    }

    /** The terminal items that are still waiting for room: bounded by the record capacity and
     * readable, so a fault leaves a trace instead of a lost audit. */
    public synchronized List<String> unresolvedTerminals() {
        List<String> pending = new ArrayList<>();
        for (CapacitySlot slot : capacitySlots.values()) {
            pending.add(slot.effectRef() + "@" + slot.maxRecords() + "r/" + slot.maxBytes() + "b");
        }
        return pending;
    }

    public synchronized List<Long> references() {
        return new ArrayList<>(records.keySet());
    }
}
