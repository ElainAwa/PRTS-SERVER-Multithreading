/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable world-set view for the internal P2 planning path; it carries identity, generation,
 * lifecycle and pinning, never a world handle or a loaded chunk. The class is not wired into the
 * production tick path; it is the data shape a later authorized world-set leaf will consume. */
final class WorldSetSnapshot {

    /** The lifecycle states a world-set view may name. The transition rules belong to the later
     * lifecycle owner; this type only carries the state. */
    enum Lifecycle {
        CREATING,
        LOADING,
        READY,
        PINNED,
        UNLOADING,
        RECLAIMING,
        GONE
    }

    /** One world at one generation. The pinning flag is valid only for an online state. */
    record World(String worldId, long generation, Lifecycle lifecycle, boolean pinned) {

        World {
            if (worldId == null || worldId.isEmpty()) {
                throw new IllegalArgumentException("world id is empty");
            }
            if (generation < 0L) {
                throw new IllegalArgumentException("world generation is negative");
            }
            if (lifecycle == null) {
                throw new IllegalArgumentException("world lifecycle is null");
            }
            if (pinned && lifecycle != Lifecycle.READY && lifecycle != Lifecycle.PINNED) {
                throw new IllegalArgumentException("a pinned world must be online");
            }
        }
    }

    /** The immutable set at one world-set generation. The list is sorted by world id and copied, so
     * a reader cannot mutate the view behind the generation. */
    record Snapshot(long generation, List<World> worlds) {

        Snapshot {
            if (generation < 0L) {
                throw new IllegalArgumentException("world-set generation is negative");
            }
            List<World> sorted = new ArrayList<>(worlds == null ? List.of() : worlds);
            sorted.sort(Comparator.comparing(World::worldId));
            Set<String> ids = new HashSet<>();
            for (World world : sorted) {
                if (!ids.add(world.worldId())) {
                    throw new IllegalArgumentException("duplicate world id " + world.worldId());
                }
            }
            worlds = List.copyOf(sorted);
        }

        World world(String worldId) {
            for (World world : worlds) {
                if (world.worldId().equals(worldId)) {
                    return world;
                }
            }
            return null;
        }
    }

    enum EventKind {
        CREATED,
        CHANGED,
        REMOVED
    }

    /** One difference between two snapshots, with both sides kept so a consumer can tell a state
     * change from a generation change without consulting either snapshot again. */
    record Event(EventKind kind, World previous, World current, long tick) {

        Event {
            if (kind == null || tick < 0L) {
                throw new IllegalArgumentException("invalid world-set event");
            }
            if ((kind == EventKind.CREATED && (previous != null || current == null))
                || (kind == EventKind.REMOVED && (previous == null || current != null))
                || (kind == EventKind.CHANGED && (previous == null || current == null))) {
                throw new IllegalArgumentException("event sides do not match " + kind);
            }
        }
    }

    private WorldSetSnapshot() {
    }

    /** Captures one immutable view. Reading it only sorts ids and copies records; it never asks a
     * world for a chunk or a handle. */
    static Snapshot capture(long generation, List<World> worlds) {
        return new Snapshot(generation, worlds);
    }

    /** The deterministic event list between two snapshots. A generation change and a lifecycle
     * change are one changed event per world; a new or absent id is created or removed. */
    static List<Event> diff(Snapshot previous, Snapshot current, long tick) {
        List<Event> events = new ArrayList<>();
        if (previous == null && current == null) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        if (previous != null) {
            for (World world : previous.worlds()) {
                ids.add(world.worldId());
            }
        }
        if (current != null) {
            for (World world : current.worlds()) {
                ids.add(world.worldId());
            }
        }
        List<String> ordered = new ArrayList<>(ids);
        ordered.sort(String::compareTo);
        for (String id : ordered) {
            World before = previous == null ? null : previous.world(id);
            World after = current == null ? null : current.world(id);
            if (before == null && after != null) {
                events.add(new Event(EventKind.CREATED, null, after, tick));
            } else if (before != null && after == null) {
                events.add(new Event(EventKind.REMOVED, before, null, tick));
            } else if (before != null && !before.equals(after)) {
                events.add(new Event(EventKind.CHANGED, before, after, tick));
            }
        }
        return List.copyOf(events);
    }

    /** The ids a plan, the arena or the commit path names that the snapshot does not carry. The
     * answer is sorted and duplicate-free; an empty list is the internal world-id coherence result.
     * This is a test/internal assertion, not a production reading. */
    static List<String> missingWorldIds(Snapshot snapshot, List<String> planWorldIds,
                                        List<String> arenaWorldIds, List<String> commitWorldIds) {
        Set<String> missing = new HashSet<>();
        addMissing(missing, snapshot, planWorldIds);
        addMissing(missing, snapshot, arenaWorldIds);
        addMissing(missing, snapshot, commitWorldIds);
        List<String> ordered = new ArrayList<>(missing);
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }

    private static void addMissing(Set<String> missing, Snapshot snapshot, List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (snapshot == null || id == null || snapshot.world(id) == null) {
                missing.add(id == null ? "" : id);
            }
        }
    }
}
