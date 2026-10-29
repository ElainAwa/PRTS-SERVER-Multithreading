/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.ArrayList;
import java.util.List;

/** The window-level world-set readiness the opt-in readouts consume: current world count, world
 * ids a plan or commit order names without the current set, and the number of set-change events
 * since the last reset. The instance is owned by the kernel and updated on the server thread; it
 * holds only ids and counters, never a world handle. */
public final class WorldSetReadout {

    private WorldSetSnapshot.Snapshot snapshot;
    private long setChangeEvents;
    private long idMismatch;

    /** Takes the set of this tick and compares it with the previous one. The first observation is
     * the baseline (no event); later creations/removals count one event each. */
    public synchronized void observe(List<String> worldIds, List<String> planWorldIds,
                                     List<String> commitWorldIds) {
        List<WorldSetSnapshot.World> worlds = new ArrayList<>();
        if (worldIds != null) {
            for (String worldId : worldIds) {
                if (worldId != null && !worldId.isEmpty()) {
                    worlds.add(new WorldSetSnapshot.World(worldId, 0L,
                        WorldSetSnapshot.Lifecycle.READY, false));
                }
            }
        }
        WorldSetSnapshot.Snapshot next = WorldSetSnapshot.capture(
            snapshot == null ? 0L : snapshot.generation(), worlds);
        List<WorldSetSnapshot.Event> events = WorldSetSnapshot.diff(snapshot, next, setChangeEvents);
        if (snapshot != null) {
            setChangeEvents += events.size();
        }
        if (!events.isEmpty()) {
            next = WorldSetSnapshot.capture(next.generation() + 1L, next.worlds());
        }
        snapshot = next;
        idMismatch = WorldSetSnapshot.missingWorldIds(next, planWorldIds, List.of(), commitWorldIds)
            .size();
    }

    /** The worlds the current set carries, 0 before the first observation or after reset. */
    public synchronized long worldCount() {
        return snapshot == null ? 0L : snapshot.worlds().size();
    }

    /** The world ids named by the current plan or commit order that the set does not carry. */
    public synchronized long idMismatch() {
        return idMismatch;
    }

    /** World-set creation and removal events since the last reset; the first observation is the
     * baseline and adds none. */
    public synchronized long setChangeEvents() {
        return setChangeEvents;
    }

    /** Clears the baseline and both counters; the next observation starts a new window. */
    public synchronized void reset() {
        snapshot = null;
        setChangeEvents = 0L;
        idMismatch = 0L;
    }
}
