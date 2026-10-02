/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;

/**
 * The one place the write-back reaches a live entity.
 *
 * <p>Everything here runs on the thread that owns the tick. A worker never calls this class: the
 * merge and the commit segment are its only callers, and both are bound to the server thread. A
 * world is found by the key the platform lists it under - the same key the write-right guard stamps
 * its world generations with, so the generation a write-back carries can be compared at the commit -
 * and an entity by the identity the frozen view carries.</p>
 *
 * <p>Nothing here keeps a reference across calls. A lookup is answered from the level of this tick
 * and the reference is dropped when the call returns, which is what lets the write-back hold plain
 * numbers between the merge and the commit instead of a host object graph.</p>
 */
public final class LiveEntityAccess {

    private LiveEntityAccess() {
    }

    /**
     * Finds the level one world key names.
     *
     * <p>A process that has no server yet - a unit test, or a check that runs before the platform
     * is up - answers {@code null} instead of throwing: a write-back that cannot find its world does
     * not land, and that is a refusal the caller counts rather than a fault that breaks the tick.</p>
     *
     * @param worldId the world key, as the platform lists it
     * @return the level, or {@code null} when no loaded world carries that key
     */
    public static ServerLevel level(String worldId) {
        if (worldId == null) {
            return null;
        }
        try {
            for (World world : Bukkit.getWorlds()) {
                if (worldId.equals(world.getKey().toString())) {
                    return ((CraftWorld) world).getHandle();
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /**
     * Finds one entity of a level by its identity.
     *
     * @param level    the level to search
     * @param entityId the identity the frozen view carries
     * @return the entity, or {@code null} when it is no longer in that level
     */
    public static Entity entity(ServerLevel level, int entityId) {
        return level == null ? null : level.getEntity(entityId);
    }

    /**
     * Writes the whitelisted kinematics of one entity.
     *
     * <p>The position is written through the entity itself and not by a teleport: a teleport is a
     * different act with its own event and passenger handling, and the domain owns only the five
     * values below. Yaw and pitch live as floats on an entity, so the value written is the float the
     * view read - normalizing a yaw or clamping a pitch never leaves that set of values.</p>
     *
     * @param entity the entity to write
     * @param x      position on x
     * @param y      position on y
     * @param z      position on z
     * @param yaw    yaw in degrees
     * @param pitch  pitch in degrees
     * @param velX   velocity on x
     * @param velY   velocity on y
     * @param velZ   velocity on z
     */
    public static void write(Entity entity, double x, double y, double z, double yaw, double pitch,
                             double velX, double velY, double velZ) {
        entity.setPos(x, y, z);
        entity.setYRot((float) yaw);
        entity.setXRot((float) pitch);
        entity.setDeltaMovement(velX, velY, velZ);
    }

    /**
     * Reads the whitelisted kinematics of one entity into a row.
     *
     * <p>The identity fields of the row are carried over unchanged: the reader answers what the
     * entity holds for the same entity, not who it is.</p>
     *
     * @param entity the entity to read
     * @param row    the row whose identity the answer keeps
     * @return the row the world holds
     */
    public static StateHasher.Slice read(Entity entity, StateHasher.Slice row) {
        return new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(), row.entitySeq(),
            posX(entity), posY(entity), posZ(entity), yaw(entity), pitch(entity), velX(entity),
            velY(entity), velZ(entity), row.flags(), row.slotGeneration(), row.segmentRef());
    }

    /**
     * Answers whether the world already holds exactly the kinematics of one row.
     *
     * <p>This is the predicate of the takeover boundary: only a row the host itself already holds,
     * bit for bit, may be taken over without changing host semantics.</p>
     *
     * @param entity the entity the row names
     * @param row    the row the domain computed
     * @return whether every value of the row is already the value of the world
     */
    public static boolean identical(Entity entity, StateHasher.Slice row) {
        return KinematicIdentity.matches(row, read(entity, row));
    }

    /** @param entity the entity to read @return position on x */
    public static double posX(Entity entity) {
        return entity.getX();
    }

    /** @param entity the entity to read @return position on y */
    public static double posY(Entity entity) {
        return entity.getY();
    }

    /** @param entity the entity to read @return position on z */
    public static double posZ(Entity entity) {
        return entity.getZ();
    }

    /** @param entity the entity to read @return yaw in degrees */
    public static double yaw(Entity entity) {
        return entity.getYRot();
    }

    /** @param entity the entity to read @return pitch in degrees */
    public static double pitch(Entity entity) {
        return entity.getXRot();
    }

    /** @param entity the entity to read @return velocity on x */
    public static double velX(Entity entity) {
        return entity.getDeltaMovement().x;
    }

    /** @param entity the entity to read @return velocity on y */
    public static double velY(Entity entity) {
        return entity.getDeltaMovement().y;
    }

    /** @param entity the entity to read @return velocity on z */
    public static double velZ(Entity entity) {
        return entity.getDeltaMovement().z;
    }
}
