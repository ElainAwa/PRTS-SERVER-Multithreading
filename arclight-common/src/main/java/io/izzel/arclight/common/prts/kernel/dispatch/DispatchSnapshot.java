/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the read-only entity view of one tick.
 *
 * <p>This is the one place the dispatcher reads the host: it runs on the tick thread, copies plain
 * numbers out of the live entities and never keeps a reference to one. A world that cannot be read
 * contributes nothing instead of failing the tick, and a view is bounded per world, so a crowded
 * world cannot turn one tick into an unbounded copy.</p>
 *
 * <p>A world is stamped with the key the platform lists it under - the same key the world lifecycle
 * hands the write-right guard - so the generation a write-back carries is the generation the commit
 * compares, and a write-back can never land in a world that was unloaded and rebuilt meanwhile.</p>
 *
 * <p>Players are not candidates. Their position is driven by their own connection, and a domain that
 * owns an entity has to own what moves it; until the domain owns the player tick as well, writing a
 * player back would fight the client for the same value. Both arms and the write-back therefore
 * cover the same candidate set: every entity that is not a player.</p>
 */
public final class DispatchSnapshot {

    private DispatchSnapshot() {
    }

    /**
     * Copies the entity kinematics of every live world.
     *
     * @param worldEpochs the generation to stamp each world with
     * @return one view per world, in the order the host lists the worlds
     */
    public static List<EntityCandidateView> capture(WorldEpochSource worldEpochs) {
        List<EntityCandidateView> views = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            try {
                views.add(capture(world, worldEpochs.epochOf(world.getName())));
            } catch (Throwable ignored) {
                // A world that cannot be listed contributes no candidates; the tick continues.
            }
        }
        return views;
    }

    private static EntityCandidateView capture(World world, long worldEpoch) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(world.getKey().toString(),
            worldEpoch);
        for (Entity entity : world.getEntities()) {
            if (builder.full()) {
                break;
            }
            if (entity instanceof Player) {
                continue;
            }
            try {
                Location location = entity.getLocation();
                builder.add(entity.getEntityId(), location.getBlockX() >> 4,
                    location.getBlockZ() >> 4, location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), entity.getVelocity().getX(),
                    entity.getVelocity().getY(), entity.getVelocity().getZ(), 0L);
            } catch (Throwable ignored) {
                // An entity that cannot be read contributes no candidate.
            }
        }
        return builder.build();
    }

    /** Supplies the generation of a world to a snapshot. */
    @FunctionalInterface
    public interface WorldEpochSource {

        /**
         * Answers the generation of one world.
         *
         * @param worldId the world name
         * @return the generation the view is stamped with
         */
        long epochOf(String worldId);
    }
}
