/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

/**
 * One state slot and the lease its owner holds: a release that no longer matches the lease is
 * refused and never touches the buffer of the batch that was given the slot next.
 */
public final class ArenaSlot {

    /** The lifecycle of one slot. */
    public enum State {
        /** Not owned by any batch. */
        FREE,
        /** Owned by a batch that has not published yet. */
        PINNED,
        /** Published by its owner and waiting for the merge. */
        PUBLISHED
    }

    /**
     * Ownership record of one claim: the slot, the owner and the buffer that claim writes.
     */
    public record Lease(SegmentRef segment, int slotIndex, long generation, long ownerBatchId,
                        long ownerPlanEpoch, ArenaScratch scratch) {

        public Lease {
            if (segment == null || slotIndex < 0 || ownerBatchId == 0L || scratch == null) {
                throw new IllegalArgumentException("a lease needs a segment, a slot, an owner and a buffer");
            }
        }

        public String segmentKey() {
            return segment.key();
        }
    }

    /** What a release attempt did; only {@link #RELEASED} changed the slot. */
    public enum Release {
        /** The lease still owned the slot and the slot was returned to its segment. */
        RELEASED,
        /** The same lease was released before; the slot is free at the generation that release left. */
        ALREADY_RELEASED,
        /** The slot is owned by another batch or moved on; nothing about it was changed. */
        STALE_LEASE,
        /** The lease names a segment the ledger does not hold. */
        FOREIGN_SEGMENT
    }

    private final String worldId;
    private final String regionId;
    private final int segmentKind;
    private final int index;
    private final SlotGeneration generation = new SlotGeneration();
    private volatile ArenaScratch scratch = new ArenaScratch();
    private volatile State state = State.FREE;
    private volatile long ownerBatchId;

    ArenaSlot(String worldId, String regionId, int segmentKind, int index) {
        this.worldId = worldId;
        this.regionId = regionId;
        this.segmentKind = segmentKind;
        this.index = index;
    }

    public SegmentRef segment() {
        return new SegmentRef(worldId, regionId, segmentKind);
    }

    public int index() {
        return index;
    }

    public SlotRef ref() {
        return new SlotRef(worldId, regionId, segmentKind, index, generation.value());
    }

    /** @return the buffer of the current owner; unreachable once the slot is free */
    public ArenaScratch scratch() {
        return scratch;
    }

    public State state() {
        return state;
    }

    public long ownerBatchId() {
        return ownerBatchId;
    }

    public long generation() {
        return generation.value();
    }

    synchronized boolean claim(long batchId, int capacity) {
        if (state != State.FREE || batchId == 0L) {
            return false;
        }
        ArenaScratch buffer = scratch;
        if (buffer == null) {
            buffer = new ArenaScratch();
            scratch = buffer;
        }
        buffer.reset(capacity);
        ownerBatchId = batchId;
        state = State.PINNED;
        return true;
    }

    public Lease lease(long planEpoch) {
        return new Lease(segment(), index, generation.value(), ownerBatchId, planEpoch, scratch);
    }

    public boolean publish(Lease lease) {
        if (lease == null || state != State.PINNED || ownerBatchId != lease.ownerBatchId()
            || generation.value() != lease.generation()) {
            return false;
        }
        state = State.PUBLISHED;
        return true;
    }

    /**
     * Releases the slot if and only if the lease still owns it. Only the matching release bumps the
     * generation; a release the owner did not confirm retires the buffer, so a worker still in its
     * body cannot write into the next claim's buffer.
     */
    public Release release(Lease lease, boolean ownerConfirmed) {
        if (lease != null && state != State.FREE && ownerBatchId == lease.ownerBatchId()
            && generation.value() == lease.generation()) {
            generation.bump();
            ownerBatchId = 0L;
            state = State.FREE;
            if (!ownerConfirmed) {
                scratch = null;
            }
            return Release.RELEASED;
        }
        if (lease != null && state == State.FREE && ownerBatchId == 0L
            && generation.value() == lease.generation() + 1L) {
            return Release.ALREADY_RELEASED;
        }
        return Release.STALE_LEASE;
    }

    /** Releases the slot whoever holds it; the generation still moves first. */
    boolean forceRelease() {
        if (state == State.FREE && ownerBatchId == 0L) {
            return false;
        }
        generation.bump();
        ownerBatchId = 0L;
        state = State.FREE;
        scratch.reset(0);
        return true;
    }
}
