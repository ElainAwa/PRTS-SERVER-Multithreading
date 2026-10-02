/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The slots of one region of one world. Every pin has exactly one release, and a release is judged
 * against the lease it was handed, so a slot already given to the next batch is never freed here.
 */
public final class ArenaSegment {

    private final SegmentRef ref;
    private final SlotGeneration generation = new SlotGeneration();
    private final List<ArenaSlot> slots = new ArrayList<>();
    private final Deque<ArenaSlot> free = new ArrayDeque<>();

    ArenaSegment(SegmentRef ref) {
        this.ref = ref;
    }

    /** @return the identity of the segment */
    public SegmentRef ref() {
        return ref;
    }

    /** @return the generation of the segment */
    public long generation() {
        return generation.value();
    }

    /** @return how many slots the segment ever created */
    public int slotCount() {
        return slots.size();
    }

    /** @return how many slots are owned by a batch right now */
    public int pinnedCount() {
        int pinned = 0;
        for (ArenaSlot slot : slots) {
            if (slot.state() != ArenaSlot.State.FREE) {
                pinned++;
            }
        }
        return pinned;
    }

    /** @return the slots the segment created, in creation order */
    public synchronized java.util.List<ArenaSlot> slots() {
        return java.util.List.copyOf(slots);
    }

    /**
     * Hands a free slot to one batch.
     *
     * @param batchId  the batch that becomes the owner
     * @param capacity how many entity rows the batch will write
     * @return the claimed slot, or {@code null} when the batch cannot own one
     */
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

    /**
     * Releases one slot for reuse, if and only if the lease still owns it.
     *
     * @param lease          the lease the caller holds
     * @param ownerConfirmed whether the owning batch has finished its body
     * @return what the attempt did; a stale lease never changes the current owner
     */
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
}
