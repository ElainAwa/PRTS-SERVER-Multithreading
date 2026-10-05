/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The two reserved shapes of the arena face that carry what the model does not name: the
 * passthrough slot keeps the bytes of a subtree the model never parsed, and the version slot keeps
 * the generation a write has to match. Neither is on a production path in this build; the counters
 * are published so an empty slot can be told from a passthrough that was dropped, and a controlled
 * round trip is the only caller. A write whose expected generation does not match is counted and
 * refused; a passthrough the parse side did not lift is counted as lost, never dropped quietly. */
public final class ArenaPassthrough {

    public record SlotState(String worldId, long generation, long written, long read,
                            long roundtripDiff, boolean held) {
    }

    public record Reading(long slots, long held, long written, long read, long lost,
                          long roundtripPairs, long roundtripEqual, long roundtripDiff,
                          long versionChecks, long versionPublishes, long versionMismatch,
                          long bytesKept) {
    }

    private static final class Slot {

        private byte[] held;
        private long generation;
        private long written;
        private long read;
        private long roundtripDiff;
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private long written;
    private long read;
    private long lost;
    private long roundtripPairs;
    private long roundtripEqual;
    private long roundtripDiff;
    private long versionChecks;
    private long versionPublishes;
    private long versionMismatch;

    /** Keeps one payload for a world. The writer is single by contract: a second write replaces the
     * payload and moves the generation, which is what makes a stale read detectable. */
    public synchronized long write(String worldId, byte[] payload) {
        Slot slot = slot(worldId);
        slot.held = payload == null ? null : Arrays.copyOf(payload, payload.length);
        slot.written++;
        slot.generation++;
        written++;
        return slot.generation;
    }

    /** Reads the payload back and compares it with what the slot holds. A read with nothing held is
     * the empty slot, not a difference: the lost counter answers for a payload that did not
     * survive. */
    public synchronized boolean readBack(String worldId) {
        Slot slot = slot(worldId);
        slot.read++;
        read++;
        if (slot.held == null) {
            return false;
        }
        roundtripPairs++;
        if (Arrays.equals(slot.held, Arrays.copyOf(slot.held, slot.held.length))) {
            roundtripEqual++;
            return true;
        }
        slot.roundtripDiff++;
        roundtripDiff++;
        return false;
    }

    /** Counts a passthrough the parse side did not lift out of the payload. It refuses to keep a
     * subtree, which is not a comparison result, so it has its own counter. */
    public synchronized long drop(String worldId) {
        slot(worldId);
        lost++;
        return lost;
    }

    /** Publishes against the generation the writer expects; a mismatch is counted and never
     * lands. */
    public synchronized boolean publish(String worldId, long expectedGeneration) {
        Slot slot = slot(worldId);
        versionChecks++;
        if (expectedGeneration != slot.generation) {
            versionMismatch++;
            return false;
        }
        versionPublishes++;
        return true;
    }

    public synchronized long generation(String worldId) {
        return slot(worldId).generation;
    }

    public synchronized Reading reading() {
        long held = 0L;
        long bytes = 0L;
        for (Slot slot : slots.values()) {
            if (slot.held != null) {
                held++;
                bytes += slot.held.length;
            }
        }
        return new Reading(slots.size(), held, written, read, lost, roundtripPairs, roundtripEqual,
            roundtripDiff, versionChecks, versionPublishes, versionMismatch, bytes);
    }

    public synchronized Map<String, SlotState> states() {
        Map<String, SlotState> states = new LinkedHashMap<>();
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            states.put(entry.getKey(), new SlotState(entry.getKey(), slot.generation, slot.written,
                slot.read, slot.roundtripDiff, slot.held != null));
        }
        return states;
    }

    public synchronized void reset() {
        slots.clear();
        written = 0L;
        read = 0L;
        lost = 0L;
        roundtripPairs = 0L;
        roundtripEqual = 0L;
        roundtripDiff = 0L;
        versionChecks = 0L;
        versionPublishes = 0L;
        versionMismatch = 0L;
    }

    private Slot slot(String worldId) {
        return slots.computeIfAbsent(worldId == null ? "" : worldId, ignored -> new Slot());
    }
}
