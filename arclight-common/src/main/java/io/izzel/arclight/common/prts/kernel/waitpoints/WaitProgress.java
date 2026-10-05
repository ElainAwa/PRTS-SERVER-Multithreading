/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** The progress side of a wait point: the value of the one signal form the row declares, the reading
 * it is taken from, and whether it moved since the previous read. A row whose producer is not built
 * yet stays explicitly unbound and publishes a zero reading, so a missing signal is visible instead
 * of looking like a row that never made progress. */
public final class WaitProgress {

    /** One published reading. The form says how to read it: a count is a cumulative value with a
     * per-read delta, a watermark is an instant level whose delta is the movement since the previous
     * read, and a heartbeat is a marker whose delta answers whether it advanced at all. */
    public record Reading(String wpId, Dec19Elements.SignalKind kind, String fieldRef, boolean bound,
                          String source, long value, long previous, long delta) {

        public boolean advancing() {
            return bound && delta > 0L;
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /** Declares the signal one row publishes. */
    public synchronized void declare(String wpId, Dec19Elements.ProgressSignal signal) {
        if (wpId == null || signal == null) {
            return;
        }
        Slot existing = slots.get(wpId);
        if (existing == null) {
            slots.put(wpId, new Slot(signal.kind(), signal.fieldRef()));
        }
    }

    /** Binds the reading a row's value comes from. A bound row is read from its producer; an unbound
     * row publishes the value a call site handed in, or zero. */
    public synchronized void bind(String wpId, String source, LongSupplier reading) {
        Slot slot = slots.get(wpId);
        if (slot == null || reading == null) {
            return;
        }
        slot.source = source;
        slot.reading = reading;
    }

    /** Records the value a call site handed in with its observation. */
    public void noteObserved(String wpId, long value) {
        Slot slot;
        synchronized (this) {
            slot = slots.get(wpId);
        }
        if (slot != null) {
            slot.observed = value;
        }
    }

    /** Reads one row. */
    public synchronized Reading read(String wpId) {
        Slot slot = slots.get(wpId);
        if (slot == null) {
            return null;
        }
        long value = slot.current();
        long previous = slot.value;
        slot.value = value;
        return new Reading(wpId, slot.kind, slot.fieldRef, slot.reading != null, slot.source(), value,
            previous, value - previous);
    }

    /** Reads every declared row, in declaration order. */
    public synchronized List<Reading> readings() {
        List<Reading> readings = new ArrayList<>(slots.size());
        for (String wpId : slots.keySet()) {
            readings.add(read(wpId));
        }
        return readings;
    }

    /** The current value of every declared row, in declaration order. */
    public synchronized Map<String, Long> values() {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            values.put(entry.getKey(), entry.getValue().current());
        }
        return values;
    }

    /** How many rows carry a bound producer. */
    public synchronized int boundCount() {
        int bound = 0;
        for (Slot slot : slots.values()) {
            if (slot.reading != null) {
                bound++;
            }
        }
        return bound;
    }

    public synchronized int declaredCount() {
        return slots.size();
    }

    public synchronized void reset() {
        for (Slot slot : slots.values()) {
            slot.observed = 0L;
            slot.value = 0L;
        }
    }

    /** One row's slot. The producer is read outside the lock and the counters are volatile: a reader
     * of the readout never blocks the thread that reports a wait. */
    private static final class Slot {

        private final Dec19Elements.SignalKind kind;
        private final String fieldRef;
        private volatile String source;
        private volatile LongSupplier reading;
        private volatile long observed;
        private volatile long value;

        private Slot(Dec19Elements.SignalKind kind, String fieldRef) {
            this.kind = kind;
            this.fieldRef = fieldRef;
        }

        private long current() {
            LongSupplier supplier = reading;
            return supplier == null ? observed : supplier.getAsLong();
        }

        private String source() {
            LongSupplier supplier = reading;
            return supplier == null ? "unbound" : (source == null ? "unbound" : source);
        }
    }
}
