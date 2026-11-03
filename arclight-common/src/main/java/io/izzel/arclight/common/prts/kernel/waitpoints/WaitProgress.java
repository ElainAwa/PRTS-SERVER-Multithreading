/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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

    /** One per-world value a bound row publishes: the cumulative generation and light progress. */
    public record WorldValue(String worldId, long epoch, long value, long light) {
    }

    /** One published per-world reading. It exists only for a world the producer actually carries;
     * a world that is absent, stale or untracked publishes no row instead of a zero. The generation
     * value/delta is the formal reading; the light pair is carried for the internal test/readout
     * path and is not a separate formal key family. */
    public record WorldReading(String wpId, String worldId, long epoch, boolean bound, String source,
                               long value, long previous, long delta, long lightValue,
                               long lightPrevious, long lightDelta) {
    }

    private static final int MAX_WORLD_READINGS = 256;

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Map<String, WorldSource> worldSources = new LinkedHashMap<>();
    private final Map<String, Map<String, WorldSlot>> worldSlots = new LinkedHashMap<>();

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

    /** Binds the per-world values one row publishes. Only worlds present in the producer's list are
     * read; the value is cumulative and the delta is measured from the previous export of the same
     * world and epoch. A world absent from the list keeps no slot and publishes no zero. */
    public synchronized void bindWorlds(String wpId, String source,
                                        Supplier<List<WorldValue>> reading) {
        if (wpId == null || reading == null) {
            return;
        }
        worldSources.put(wpId, new WorldSource(source, reading));
        worldSlots.computeIfAbsent(wpId, ignored -> new LinkedHashMap<>());
    }

    /** Hands the per-world binding back; no world reading is published after it. */
    public synchronized void unbindWorlds(String wpId) {
        worldSources.remove(wpId);
        worldSlots.remove(wpId);
    }

    /** Reads the per-world values of one row, in the producer's order. It reads only the worlds the
     * producer currently carries and never invents a zero for an absent or stale world. */
    public synchronized List<WorldReading> worldReadings(String wpId) {
        WorldSource source = worldSources.get(wpId);
        if (source == null) {
            return List.of();
        }
        List<WorldValue> values;
        try {
            values = source.reading().get();
        } catch (RuntimeException | Error unreadable) {
            return List.of();
        }
        if (values == null || values.isEmpty()) {
            Map<String, WorldSlot> empty = worldSlots.get(wpId);
            if (empty != null) {
                empty.clear();
            }
            return List.of();
        }
        Map<String, WorldSlot> slots = worldSlots.computeIfAbsent(wpId,
            ignored -> new LinkedHashMap<>());
        List<WorldReading> readings = new ArrayList<>(Math.min(values.size(), MAX_WORLD_READINGS));
        Set<String> seen = new HashSet<>();
        for (WorldValue value : values) {
            if (value == null || value.worldId() == null || value.worldId().isBlank()
                || value.value() < 0L || !seen.add(value.worldId())) {
                continue;
            }
            if (readings.size() >= MAX_WORLD_READINGS) {
                break;
            }
            WorldSlot previous = slots.get(value.worldId());
            boolean sameEpoch = previous != null && previous.epoch == value.epoch();
            long previousValue = sameEpoch ? previous.previous : 0L;
            long previousLight = sameEpoch ? previous.lightPrevious : 0L;
            long delta = value.value() - previousValue;
            if (delta < 0L) {
                previousValue = 0L;
                delta = value.value();
            }
            long lightDelta = value.light() - previousLight;
            if (lightDelta < 0L) {
                previousLight = 0L;
                lightDelta = value.light();
            }
            slots.put(value.worldId(), new WorldSlot(value.epoch(), value.value(), value.light()));
            readings.add(new WorldReading(wpId, value.worldId(), value.epoch(), true,
                source.source(), value.value(), previousValue, delta, value.light(), previousLight,
                lightDelta));
        }
        slots.keySet().retainAll(seen);
        return List.copyOf(readings);
    }

    /** Hands a bound reading back. A row whose producer is switched off reports an unbound slot
     * again, so a value nobody produces is never published as this row's measurement. */
    public synchronized void unbind(String wpId) {
        Slot slot = slots.get(wpId);
        if (slot == null) {
            return;
        }
        slot.reading = null;
        slot.source = null;
        slot.value = 0L;
        slot.observed = 0L;
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
        worldSlots.clear();
    }

    private record WorldSource(String source, Supplier<List<WorldValue>> reading) {
    }

    private static final class WorldSlot {

        private final long epoch;
        private final long previous;
        private final long lightPrevious;

        private WorldSlot(long epoch, long previous, long lightPrevious) {
            this.epoch = epoch;
            this.previous = previous;
            this.lightPrevious = lightPrevious;
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
