/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

/**
 * One intermediate state slot: the buffer a single batch may write, its owner and its generation.
 *
 * <p>Ownership is checked twice: when the batch claims the slot and when it publishes. A slot is
 * released by bumping its generation first, so a late writer meets a generation it no longer holds
 * and is refused with a count instead of overwriting the next owner's values.</p>
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

    private final String worldId;
    private final String regionId;
    private final int segmentKind;
    private final int index;
    private final SlotGeneration generation = new SlotGeneration();
    private final ArenaScratch scratch = new ArenaScratch();
    private volatile State state = State.FREE;
    private volatile long ownerBatchId;

    ArenaSlot(String worldId, String regionId, int segmentKind, int index) {
        this.worldId = worldId;
        this.regionId = regionId;
        this.segmentKind = segmentKind;
        this.index = index;
    }

    /** @return the segment key of the slot */
    public SegmentRef segment() {
        return new SegmentRef(worldId, regionId, segmentKind);
    }

    /** @return the stable reference of the slot at its current generation */
    public SlotRef ref() {
        return new SlotRef(worldId, regionId, segmentKind, index, generation.value());
    }

    /** @return the buffer the owner writes into */
    public ArenaScratch scratch() {
        return scratch;
    }

    /** @return the current lifecycle state */
    public State state() {
        return state;
    }

    /** @return the batch that owns the slot, or zero when it is free */
    public long ownerBatchId() {
        return ownerBatchId;
    }

    /** @return the current generation of the slot */
    public long generation() {
        return generation.value();
    }

    /**
     * Takes the slot for one batch.
     *
     * @param batchId the batch that becomes the owner
     * @return whether the slot was free and is now owned by that batch
     */
    public boolean claim(long batchId) {
        if (state != State.FREE || batchId == 0L) {
            return false;
        }
        ownerBatchId = batchId;
        state = State.PINNED;
        return true;
    }

    /**
     * Publishes the values the owner wrote.
     *
     * @param batchId     the batch that claims to own the slot
     * @param generationAtClaim the generation the batch claimed at
     * @return whether the slot was owned by that batch at that generation
     */
    public boolean publish(long batchId, long generationAtClaim) {
        if (state != State.PINNED || ownerBatchId != batchId
            || generation.value() != generationAtClaim) {
            return false;
        }
        state = State.PUBLISHED;
        return true;
    }

    /**
     * Releases the slot for another owner, bumping the generation first.
     *
     * @return {@code true} when the slot carried an owner and was released
     */
    public boolean release() {
        if (state == State.FREE) {
            return false;
        }
        generation.bump();
        ownerBatchId = 0L;
        state = State.FREE;
        scratch.reset(0);
        return true;
    }
}
