/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;

/** The one place the write-back reaches a live entity. Everything here runs on the thread that
 * owns the tick. */
public final class LiveEntityAccess {

    private LiveEntityAccess() {
    }

    /** A process that has no server yet - a unit test, or a check that runs before the platform is
     * up - answers {@code null} instead of throwing: a write-back that cannot find its world does
     * not land, and that is a refusal the caller counts rather than a fault that breaks the tick.
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

    /** Finds one entity of a level by its identity. */
    public static Entity entity(ServerLevel level, int entityId) {
        return level == null ? null : level.getEntity(entityId);
    }

    /** Writes the whitelisted kinematics of one entity. The position is written through the entity
     * itself and not by a teleport: a teleport is a different act with its own event and passenger
     * handling, and the domain owns only the five values below. */
    public static void write(Entity entity, double x, double y, double z, double yaw, double pitch,
                             double velX, double velY, double velZ) {
        entity.setPos(x, y, z);
        entity.setYRot((float) yaw);
        entity.setXRot((float) pitch);
        entity.setDeltaMovement(velX, velY, velZ);
    }

    /** Reads the whitelisted kinematics of one entity into a row. The identity fields of the row
     * are carried over unchanged: the reader answers what the entity holds for the same entity,
     * not who it is. */
    public static StateHasher.Slice read(Entity entity, StateHasher.Slice row) {
        return new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(), row.entitySeq(),
            posX(entity), posY(entity), posZ(entity), yaw(entity), pitch(entity), velX(entity),
            velY(entity), velZ(entity), row.flags(), row.slotGeneration(), row.segmentRef());
    }

    /** This is the predicate of the takeover boundary: only a row the host itself already holds,
     * bit for bit, may be taken over without changing host semantics. */
    public static boolean identical(Entity entity, StateHasher.Slice row) {
        return KinematicIdentity.matches(row, read(entity, row));
    }

    public static double posX(Entity entity) {
        return entity.getX();
    }

    public static double posY(Entity entity) {
        return entity.getY();
    }

    public static double posZ(Entity entity) {
        return entity.getZ();
    }

    public static double yaw(Entity entity) {
        return entity.getYRot();
    }

    public static double pitch(Entity entity) {
        return entity.getXRot();
    }

    public static double velX(Entity entity) {
        return entity.getDeltaMovement().x;
    }

    public static double velY(Entity entity) {
        return entity.getDeltaMovement().y;
    }

    public static double velZ(Entity entity) {
        return entity.getDeltaMovement().z;
    }

    /** Compares the kinematics of two rows bit by bit. The write-back leg may be narrowed to what the
     * host itself already holds. */
    public static final class KinematicIdentity {

        private KinematicIdentity() {
        }

        /** Answers whether two rows carry the same kinematics. */
        public static boolean matches(StateHasher.Slice expected, StateHasher.Slice actual) {
            return sameDouble(expected.x(), actual.x())
                && sameDouble(expected.y(), actual.y())
                && sameDouble(expected.z(), actual.z())
                && sameFloat((float) expected.yaw(), (float) actual.yaw())
                && sameFloat((float) expected.pitch(), (float) actual.pitch())
                && sameDouble(expected.velX(), actual.velX())
                && sameDouble(expected.velY(), actual.velY())
                && sameDouble(expected.velZ(), actual.velZ());
        }

        /** Answers whether two doubles have the same raw bits. */
        public static boolean sameDouble(double left, double right) {
            return Double.doubleToRawLongBits(left) == Double.doubleToRawLongBits(right);
        }

        /** Answers whether two floats have the same raw bits. */
        public static boolean sameFloat(float left, float right) {
            return Float.floatToRawIntBits(left) == Float.floatToRawIntBits(right);
        }
    }
}
