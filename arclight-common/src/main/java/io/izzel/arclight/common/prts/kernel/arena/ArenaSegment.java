/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The slots of one region of one world: a free list, a segment generation and the pin bookkeeping.
 *
 * <p>The segment hands a slot to exactly one batch at a time. A slot that is not free is never
 * handed out again, which is the first half of the leak defence; the other half is that every pin
 * has exactly one release, counted by the ledger.</p>
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
        slot.scratch().reset(capacity);
        if (!slot.claim(batchId)) {
            free.addFirst(slot);
            return null;
        }
        return slot;
    }

    /**
     * Releases one slot for reuse.
     *
     * @param slot the slot to release
     * @return whether the slot belonged to this segment and was released
     */
    synchronized boolean release(ArenaSlot slot) {
        if (slot.segment().segmentKind() != ref.segmentKind()
            || !slot.segment().worldId().equals(ref.worldId())
            || !slot.segment().regionId().equals(ref.regionId())) {
            return false;
        }
        if (!slot.release()) {
            return false;
        }
        generation.bump();
        free.add(slot);
        return true;
    }
}