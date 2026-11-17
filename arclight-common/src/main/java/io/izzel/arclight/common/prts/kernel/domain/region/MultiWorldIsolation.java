/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The multi-world isolation ledger: owner tokens, intent queues, arena slots, commit tickets and
 * region topology are kept per (world id, world incarnation); a flush, unload, generation advance or
 * reset of one world leaves every other world's pending state untouched, and a cross-world migration
 * is refused before the source publishes when the target is unloading, gone or of another
 * incarnation. */
public final class MultiWorldIsolation {

    /** The world lifecycle state; only ACTIVE accepts a migration. */
    public enum WorldState {
        ACTIVE,
        UNLOADING,
        GONE
    }

    /** What one world currently holds. */
    public record WorldHoldings(String worldId, long incarnation, WorldState state,
                                Set<String> ownerTokens, int intents, int arenaSlots,
                                int commitTickets) {
    }

    /** The answer of one migration intent. */
    public record Migration(boolean accepted, String reason) {
    }

    private record Key(String worldId, long incarnation) {
    }

    private final Map<Key, Mutable> worlds = new LinkedHashMap<>();
    private long migrationsRefused;

    private static final class Mutable {

        private WorldState state = WorldState.ACTIVE;
        private final Set<String> owners = new LinkedHashSet<>();
        private int intents;
        private int arenaSlots;
        private int commitTickets;
    }

    /** Adds one active world incarnation; a new incarnation never inherits the old ledger. */
    public synchronized void addWorld(String worldId, long incarnation) {
        worlds.computeIfAbsent(new Key(worldId, incarnation), key -> new Mutable());
    }

    public synchronized void stopNewWork(String worldId, long incarnation) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world != null && world.state == WorldState.ACTIVE) {
            world.state = WorldState.UNLOADING;
        }
    }

    /** Records one owner token of exactly one world incarnation. */
    public synchronized boolean holdOwner(String worldId, long incarnation, String token) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world == null) {
            return false;
        }
        world.owners.add(token);
        return true;
    }

    public synchronized void holdIntent(String worldId, long incarnation, int count) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world != null) {
            world.intents += count;
        }
    }

    public synchronized void holdArenaSlot(String worldId, long incarnation, int count) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world != null) {
            world.arenaSlots += count;
        }
    }

    public synchronized void holdCommitTicket(String worldId, long incarnation, int count) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world != null) {
            world.commitTickets += count;
        }
    }

    /** Flushes one world only: another world's holders, intents and tickets are not touched. */
    public synchronized int flush(String worldId, long incarnation) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world == null) {
            return 0;
        }
        int drained = world.intents;
        world.intents = 0;
        return drained;
    }

    /** Unloads one world's rights only; another world's ledger survives unchanged. */
    public synchronized void unload(String worldId, long incarnation) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world != null && world.state != WorldState.GONE) {
            world.owners.clear();
            world.intents = 0;
            world.arenaSlots = 0;
            world.commitTickets = 0;
            world.state = WorldState.GONE;
        }
    }

    /** One world's holdings snapshot, for the isolation witness. */
    public synchronized WorldHoldings holdings(String worldId, long incarnation) {
        Mutable world = worlds.get(new Key(worldId, incarnation));
        if (world == null) {
            return new WorldHoldings(worldId, incarnation, WorldState.GONE, Set.of(), 0, 0, 0);
        }
        return new WorldHoldings(worldId, incarnation, world.state, Set.copyOf(world.owners),
            world.intents, world.arenaSlots, world.commitTickets);
    }

    /** A migration is refused before the source publishes when the target cannot accept it. */
    public synchronized Migration migrate(String sourceWorld, long sourceIncarnation,
                                          String targetWorld, long targetIncarnation) {
        Mutable target = worlds.get(new Key(targetWorld, targetIncarnation));
        if (target == null || target.state != WorldState.ACTIVE) {
            migrationsRefused++;
            return new Migration(false, target == null ? "target-unknown" : "target-not-active");
        }
        return new Migration(true, "source-may-publish");
    }

    public synchronized long migrationsRefused() {
        return migrationsRefused;
    }
}
