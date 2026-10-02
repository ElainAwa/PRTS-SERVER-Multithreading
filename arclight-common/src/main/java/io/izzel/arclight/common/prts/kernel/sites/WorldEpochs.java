/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The world lifecycle a deferred write is checked against.
 *
 * <p>A deferred write holds the world it was built for, and that world can be unloaded, replaced or
 * rebuilt before the commit segment reaches the intent. The identity of a world is not enough to see
 * that: the second world carries the same identifier. So every live world is given a generation -
 * the first time it appears, and again whenever it comes back after being absent - and an intent
 * records the generation it was frozen under. Comparing the two at the commit is what turns "this
 * write targets a world that no longer exists as it was" into a refusal with a code instead of a
 * write into a detached object.</p>
 *
 * <p>A kernel that was never told which worlds are live has no lifecycle information at all, and
 * neither refusal applies: the check reports {@link #UNTRACKED}, which is also what an intent frozen
 * in that state carries, so the comparison passes. That keeps a readout-only run, a unit test and a
 * platform that does not list its worlds from turning into a wall of refusals. An empty list counts
 * as no information, because a server that reports no world at all is not a server whose worlds were
 * all unloaded.</p>
 */
public final class WorldEpochs {

    /** Generation of a world that was never tracked; also the generation of an untracked intent. */
    public static final long UNTRACKED = 0L;

    /** Generation of a world that is known to be absent, because the tracked world set lost it. */
    public static final long UNLIVE = -1L;

    private final Map<String, Long> live = new ConcurrentHashMap<>();
    private final AtomicLong nextEpoch = new AtomicLong(1L);
    private final AtomicLong epochChanges = new AtomicLong();
    private volatile boolean tracking;

    /**
     * Records the worlds the platform reports as live for this tick.
     *
     * <p>A world that appeared receives the next generation; a world that is still there keeps the
     * one it has; a world the list no longer carries is forgotten, so it reads as absent and a later
     * reappearance is a new generation.</p>
     *
     * @param worldIds the live worlds, in the order the platform lists them
     */
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

    /**
     * Returns the generation of one world.
     *
     * @param worldId the world, or {@code null} for a write that names none
     * @return the generation of a live world, {@link #UNLIVE} for a world the tracked set lost, or
     *         {@link #UNTRACKED} when no world set was ever observed
     */
    public long epochOf(String worldId) {
        if (worldId == null || !tracking) {
            return UNTRACKED;
        }
        Long epoch = live.get(worldId);
        return epoch == null ? UNLIVE : epoch;
    }

    /** @return whether any world set was observed */
    public boolean tracking() {
        return tracking;
    }

    /** @return the worlds currently tracked as live */
    public int liveWorlds() {
        return live.size();
    }

    /** @return generations handed out since the process started */
    public long epochChanges() {
        return epochChanges.get();
    }
}
