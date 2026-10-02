/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * All segments of a process and the pin and release pairs that prove they leak nothing. A release
 * that no longer matches its lease is refused and counted; an unconfirmed segment is quarantined
 * instead of returned.
 */
public final class ArenaLedger {

    private final Map<String, ArenaSegment> segments = new LinkedHashMap<>();
    private long claims;
    private long releases;
    private long generationBumps;
    private long foreignWrites;
    private long refusals;
    private long staleReleases;
    private long repeatReleases;
    private long quarantinedSlots;

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
     * Releases one slot against the lease that holds it; the segment is looked up, never created.
     */
    public ArenaSlot.Release release(ArenaSlot.Lease lease, boolean ownerConfirmed) {
        ArenaSegment segment;
        synchronized (this) {
            segment = lease == null ? null : segments.get(lease.segmentKey());
        }
        ArenaSlot.Release result = segment == null ? ArenaSlot.Release.STALE_LEASE
            : segment.release(lease, ownerConfirmed);
        synchronized (this) {
            switch (result) {
                case RELEASED -> {
                    releases++;
                    generationBumps++;
                }
                case ALREADY_RELEASED -> repeatReleases++;
                case STALE_LEASE, FOREIGN_SEGMENT -> staleReleases++;
            }
        }
        return result;
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

    /** @return releases refused because the lease no longer owned the slot */
    public synchronized long staleReleases() {
        return staleReleases;
    }

    /** @return releases of a lease that was already released, which is a pairing, not a race */
    public synchronized long repeatReleases() {
        return repeatReleases;
    }

    /** @return slots detached by a quarantine instead of returned to the free lists */
    public synchronized long quarantinedSlots() {
        return quarantinedSlots;
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
        staleReleases = 0L;
        repeatReleases = 0L;
        quarantinedSlots = 0L;
    }

    /** Releases every pinned slot and forgets every segment; used by shutdown and by tests. */
    public synchronized void releaseAll() {
        for (ArenaSegment segment : segments.values()) {
            for (ArenaSlot slot : segment.slots()) {
                if (slot.forceRelease()) {
                    releases++;
                    generationBumps++;
                }
            }
        }
        segments.clear();
    }

    /**
     * Detaches every segment after a shutdown that did not confirm its workers ended. The slots are
     * not released, so the unpaired pins stay readable as the reason for the quarantine.
     */
    public synchronized void quarantineAll() {
        for (ArenaSegment segment : segments.values()) {
            for (ArenaSlot slot : segment.slots()) {
                if (slot.forceRelease()) {
                    quarantinedSlots++;
                }
            }
        }
        segments.clear();
    }
}
