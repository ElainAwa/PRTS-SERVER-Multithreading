/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All segments of a process, and the pin and release pairs that prove they leak nothing.
 *
 * <p>The ledger is the only place a segment is created, claimed or released, so the invariant
 * "every pin has exactly one release" can be read instead of argued: the counters only move here.
 * A claim that names a world the segment does not belong to is refused and counted, never served
 * from another world's buffer.</p>
 */
public final class ArenaLedger {

    private final Map<String, ArenaSegment> segments = new LinkedHashMap<>();
    private long claims;
    private long releases;
    private long generationBumps;
    private long foreignWrites;
    private long refusals;

    /**
     * Returns the segment of one key, creating it on first use.
     *
     * @param worldId     the world of the segment
     * @param regionId    the region of the segment
     * @param segmentKind the category of data the segment holds
     * @return the segment
     */
    public synchronized ArenaSegment segment(String worldId, String regionId, int segmentKind) {
        SegmentRef ref = new SegmentRef(worldId, regionId, segmentKind);
        return segments.computeIfAbsent(ref.key(), key -> new ArenaSegment(ref));
    }

    /**
     * Claims a slot for one batch.
     *
     * @param batchId     the batch that becomes the owner
     * @param worldId     the world of the batch
     * @param regionId    the region of the batch
     * @param segmentKind the category of data
     * @param capacity    how many entity rows the batch will write
     * @return the claimed slot, or {@code null} when no slot could be claimed
     */
    public ArenaSlot claim(long batchId, String worldId, String regionId, int segmentKind,
                           int capacity) {
        ArenaSlot slot = segment(worldId, regionId, segmentKind).claim(batchId, capacity);
        if (slot == null) {
            synchronized (this) {
                refusals++;
            }
            return null;
        }
        synchronized (this) {
            claims++;
        }
        return slot;
    }

    /**
     * Releases one slot and counts the generation bump that preceded the release.
     *
     * @param slot the slot to release
     * @return whether the slot was released
     */
    public boolean release(ArenaSlot slot) {
        ArenaSegment segment = segment(slot.segment().worldId(), slot.segment().regionId(),
            slot.segment().segmentKind());
        if (!segment.release(slot)) {
            return false;
        }
        synchronized (this) {
            releases++;
            generationBumps++;
        }
        return true;
    }

    /**
     * Notes a write attempt that named a slot it did not own.
     *
     * @return the number of such attempts so far
     */
    public synchronized long noteForeignWrite() {
        return ++foreignWrites;
    }

    /** @return slots pinned right now, summed over the segments */
    public synchronized int pinnedCount() {
        int pinned = 0;
        for (ArenaSegment segment : segments.values()) {
            pinned += segment.pinnedCount();
        }
        return pinned;
    }

    /** @return slots claimed since the last reset */
    public synchronized long claims() {
        return claims;
    }

    /** @return slots released since the last reset */
    public synchronized long releases() {
        return releases;
    }

    /** @return generation bumps since the last reset */
    public synchronized long generationBumps() {
        return generationBumps;
    }

    /** @return write attempts naming a slot the writer did not own */
    public synchronized long foreignWrites() {
        return foreignWrites;
    }

    /** @return claims refused because no slot was free */
    public synchronized long refusals() {
        return refusals;
    }

    /** @return whether every pin is matched by exactly one release */
    public synchronized boolean pinPairsHold() {
        return claims == releases && pinnedCount() == 0;
    }

    /** Releases everything and clears the counters; used by the readout reset and by tests. */
    public synchronized void reset() {
        releaseAll();
        claims = 0L;
        releases = 0L;
        generationBumps = 0L;
        foreignWrites = 0L;
        refusals = 0L;
    }

    /** Releases every pinned slot and forgets every segment; used by shutdown and by tests. */
    public synchronized void releaseAll() {
        for (ArenaSegment segment : segments.values()) {
            for (ArenaSlot slot : segment.slots()) {
                if (slot.release()) {
                    releases++;
                    generationBumps++;
                }
            }
        }
        segments.clear();
    }
}