/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
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
 *
 * <p>Every row also carries the host step of the entity it was read from, packed into the flag word
 * of the view. The classification reads only fields of the entity itself - never a block, a chunk or
 * an entity other than the one being read - so it costs the tick thread a few field reads and lets
 * the domain body state the same step the host will run instead of guessing at it.</p>
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
                    entity.getVelocity().getY(), entity.getVelocity().getZ(),
                    hostStep(entity));
            } catch (Throwable ignored) {
                // An entity that cannot be read contributes no candidate.
            }
        }
        return builder.build();
    }

    /**
     * Reads the host step of one entity into the flag word of its row.
     *
     * <p>Only the entity's own fields are read, so the answer is a property of the row and not of
     * the world around it. Two of them are moved to the domain body: an armour stand that does not
     * travel, and an item off the ground with gravity switched off. Everything else answers the
     * plain step, which is where the domain stays until that host step can be stated exactly.</p>
     *
     * @param entity the entity to classify
     * @return the flag word the row carries
     */
    private static long hostStep(Entity entity) {
        net.minecraft.world.entity.Entity handle = ((CraftEntity) entity).getHandle();
        if (handle instanceof net.minecraft.world.entity.decoration.ArmorStand armorStand) {
            // The host lets an armour stand travel only while it has physics, which is exactly the
            // negation of the two flags below; a stand without it is dragged and never moved.
            if (armorStand.isMarker() || armorStand.isNoGravity()) {
                return EntityIntegrator.STEP_LIVING_NO_PHYSICS;
            }
            return EntityIntegrator.STEP_PLAIN;
        }
        if (handle instanceof net.minecraft.world.entity.item.ItemEntity item) {
            // An item in the air always moves and is dragged by a plain constant; an item resting
            // on a block takes its drag from that block, which is a world read this snapshot does
            // not make, and gravity is applied before the move, which this step does not state.
            if (item.isNoGravity() && !item.onGround()) {
                return EntityIntegrator.STEP_ITEM_AIR;
            }
            return EntityIntegrator.STEP_PLAIN;
        }
        return EntityIntegrator.STEP_PLAIN;
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
