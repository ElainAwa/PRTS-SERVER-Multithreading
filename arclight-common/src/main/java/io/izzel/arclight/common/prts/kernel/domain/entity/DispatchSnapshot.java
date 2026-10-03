/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Takes the read-only entity view of one tick, on the tick thread: it copies plain numbers out of
 * the live entities and never keeps a reference to one. */
public final class DispatchSnapshot {

    private DispatchSnapshot() {
    }

    public static List<EntityCandidateView> capture(WorldEpochSource worldEpochs) {
        List<EntityCandidateView> views = new ArrayList<>();
        List<World> worlds;
        try {
            worlds = List.copyOf(Bukkit.getWorlds());
        } catch (Throwable ignored) {
            // A platform that cannot list its worlds contributes no candidates; the tick continues.
            return views;
        }
        for (World world : worlds) {
            try {
                String worldId = WorldKey.id(world);
                views.add(capture(world, worldId, worldEpochs.epochOf(worldId)));
            } catch (Throwable ignored) {
                // A world that cannot be read contributes no candidates; the tick continues.
            }
        }
        return views;
    }

    private static EntityCandidateView capture(World world, String worldId, long worldEpoch) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, worldEpoch);
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

    @FunctionalInterface
    public interface WorldEpochSource {

        long epochOf(String worldId);
    }

    /** The one key a world is named by: the snapshot stamps views with it, the write-back freezes it
     * and the write-right guard tracks the generation under it. */
    public static final class WorldKey {

        private WorldKey() {
        }

        public static String id(World world) {
            return world.getKey().toString();
        }

        public static String id(ServerLevel level) {
            return level.dimension().location().toString();
        }
    }
}
