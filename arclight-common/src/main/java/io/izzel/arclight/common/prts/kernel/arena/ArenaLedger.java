/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.LinkedHashMap;
import java.util.Map;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSegment.SegmentRef;

/** All segments of a process and the pin and release pairs that prove they leak nothing. */
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

    /** Returns the segment of one key, creating it on first use. */
    public synchronized ArenaSegment segment(String worldId, String regionId, int segmentKind) {
        SegmentRef ref = new SegmentRef(worldId, regionId, segmentKind);
        return segments.computeIfAbsent(ref.key(), key -> new ArenaSegment(ref));
    }

    /** Claims a slot for one batch. */
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

    /** Releases one slot against the lease that holds it; the segment is looked up, never created.
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

    /** Notes a write attempt that named a slot it did not own. */
    public synchronized long noteForeignWrite() {
        return ++foreignWrites;
    }

    public synchronized int pinnedCount() {
        int pinned = 0;
        for (ArenaSegment segment : segments.values()) {
            pinned += segment.pinnedCount();
        }
        return pinned;
    }

    public synchronized long claims() {
        return claims;
    }

    public synchronized long releases() {
        return releases;
    }

    public synchronized long generationBumps() {
        return generationBumps;
    }

    public synchronized long foreignWrites() {
        return foreignWrites;
    }

    public synchronized long refusals() {
        return refusals;
    }

    public synchronized long staleReleases() {
        return staleReleases;
    }

    public synchronized long repeatReleases() {
        return repeatReleases;
    }

    public synchronized long quarantinedSlots() {
        return quarantinedSlots;
    }

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

    /** Detaches every segment after a shutdown that did not confirm its workers ended. */
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
