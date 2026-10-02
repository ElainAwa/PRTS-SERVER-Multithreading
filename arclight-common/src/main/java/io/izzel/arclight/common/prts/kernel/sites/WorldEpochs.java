/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The world lifecycle a deferred write is checked against. A deferred write holds the world it
 * was built for, and that world can be unloaded, replaced or rebuilt before the commit segment
 * reaches the intent. */
public final class WorldEpochs {

    /** Generation of a world that was never tracked; also the generation of an untracked intent. */
    public static final long UNTRACKED = 0L;

    /** Generation of a world that is known to be absent, because the tracked world set lost it. */
    public static final long UNLIVE = -1L;

    private final Map<String, Long> live = new ConcurrentHashMap<>();
    private final AtomicLong nextEpoch = new AtomicLong(1L);
    private final AtomicLong epochChanges = new AtomicLong();
    private volatile boolean tracking;

    /** A world that appeared receives the next generation; a world that is still there keeps the
     * one it has; a world the list no longer carries is forgotten, so it reads as absent and a
     * later reappearance is a new generation. */
    public void observe(List<String> worldIds) {
        if (worldIds == null || worldIds.isEmpty()) {
            return;
        }
        tracking = true;
        Set<String> seen = new HashSet<>(worldIds);
        for (String worldId : seen) {
            if (worldId == null) {
                continue;
            }
            live.computeIfAbsent(worldId, key -> {
                epochChanges.incrementAndGet();
                return nextEpoch.getAndIncrement();
            });
        }
        live.keySet().removeIf(worldId -> !seen.contains(worldId));
    }

    /** Returns the generation of one world. */
    public long epochOf(String worldId) {
        if (worldId == null || !tracking) {
            return UNTRACKED;
        }
        Long epoch = live.get(worldId);
        return epoch == null ? UNLIVE : epoch;
    }

    public boolean tracking() {
        return tracking;
    }

    public int liveWorlds() {
        return live.size();
    }

    public long epochChanges() {
        return epochChanges.get();
    }
}
