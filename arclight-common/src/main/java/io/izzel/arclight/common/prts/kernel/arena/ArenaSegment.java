/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot.SlotGeneration;

/** The slots of one region of one world. */
public final class ArenaSegment {

    private final SegmentRef ref;
    private final SlotGeneration generation = new SlotGeneration();
    private final List<ArenaSlot> slots = new ArrayList<>();
    private final Deque<ArenaSlot> free = new ArrayDeque<>();

    ArenaSegment(SegmentRef ref) {
        this.ref = ref;
    }

    public SegmentRef ref() {
        return ref;
    }

    public long generation() {
        return generation.value();
    }

    public int slotCount() {
        return slots.size();
    }

    public int pinnedCount() {
        int pinned = 0;
        for (ArenaSlot slot : slots) {
            if (slot.state() != ArenaSlot.State.FREE) {
                pinned++;
            }
        }
        return pinned;
    }

    public synchronized java.util.List<ArenaSlot> slots() {
        return java.util.List.copyOf(slots);
    }

    /** Hands a free slot to one batch. */
    synchronized ArenaSlot claim(long batchId, int capacity) {
        ArenaSlot slot = free.poll();
        if (slot == null) {
            slot = new ArenaSlot(ref.worldId(), ref.regionId(), ref.segmentKind(), slots.size());
            slots.add(slot);
        }
        if (!slot.claim(batchId, capacity)) {
            free.addFirst(slot);
            return null;
        }
        return slot;
    }

    /** Releases one slot for reuse, if and only if the lease still owns it. */
    synchronized ArenaSlot.Release release(ArenaSlot.Lease lease, boolean ownerConfirmed) {
        if (lease == null) {
            return ArenaSlot.Release.STALE_LEASE;
        }
        if (lease.segment().segmentKind() != ref.segmentKind()
            || !lease.segment().worldId().equals(ref.worldId())
            || !lease.segment().regionId().equals(ref.regionId())) {
            return ArenaSlot.Release.FOREIGN_SEGMENT;
        }
        if (lease.slotIndex() < 0 || lease.slotIndex() >= slots.size()) {
            return ArenaSlot.Release.STALE_LEASE;
        }
        ArenaSlot slot = slots.get(lease.slotIndex());
        ArenaSlot.Release result = slot.release(lease, ownerConfirmed);
        if (result == ArenaSlot.Release.RELEASED) {
            generation.bump();
            free.add(slot);
        }
        return result;
    }

    /** A segment is never shared across worlds: the same region name in another world is another
     * segment, and a claim that names a different world is refused rather than reusing the buffer. */
    public record SegmentRef(String worldId, String regionId, int segmentKind) {

        /** Validates the segment identity. */
        public SegmentRef {
            if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
                throw new IllegalArgumentException("a segment needs a world and a region");
            }
        }

        public String key() {
            return worldId + "|" + regionId + "|" + segmentKind;
        }
    }
}
