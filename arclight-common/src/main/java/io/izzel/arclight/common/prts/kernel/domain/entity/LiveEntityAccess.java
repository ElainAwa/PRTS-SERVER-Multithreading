/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;

/** The one place the write-back reaches a live entity; everything here runs on the thread that owns the tick. */
public final class LiveEntityAccess {

    private LiveEntityAccess() {
    }

    /** A process with no server yet answers null instead of throwing: a write-back that cannot find
     * its world does not land, and that is a refusal the caller counts rather than a fault that breaks the tick. */
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

    /** Writes the whitelisted kinematics of one entity: position through the entity itself, not a
     * teleport, which is a different act with its own event and passenger handling. */
    public static void write(Entity entity, double x, double y, double z, double yaw, double pitch,
                             double velX, double velY, double velZ) {
        entity.setPos(x, y, z);
        entity.setYRot((float) yaw);
        entity.setXRot((float) pitch);
        entity.setDeltaMovement(velX, velY, velZ);
        // The host tick owns the tracker send pass; the write-back must not send a second time.
    }

    /** Reads the whitelisted kinematics of one entity into a row; the identity fields of the row are
     * carried over unchanged, so the reader answers what the entity holds, not who it is. */
    public static StateHasher.Slice read(Entity entity, StateHasher.Slice row) {
        return new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(), row.entitySeq(),
            posX(entity), posY(entity), posZ(entity), yaw(entity), pitch(entity), velX(entity),
            velY(entity), velZ(entity), row.flags(), row.slotGeneration(), row.segmentRef());
    }

    /** Reads the extended scalar matrix once on the tick thread; no live object leaves this call. */
    public static EntityRowFields readFields(Entity entity) {
        boolean removed = entity.isRemoved();
        boolean passenger = entity.isPassenger();
        int tickCount = entity.tickCount;
        java.util.Set<String> groups = new java.util.LinkedHashSet<>();
        groups.add("LIFECYCLE");
        double health = -1.0;
        double maxHealth = -1.0;
        int air = -1;
        int maxAir = -1;
        if (entity instanceof LivingEntity living) {
            health = living.getHealth();
            maxHealth = living.getMaxHealth();
            air = living.getAirSupply();
            maxAir = living.getMaxAirSupply();
            groups.add("HEALTH");
            groups.add("AIR");
        }
        boolean hasTarget = false;
        boolean noAi = false;
        int brainMask = -1;
        int goalMask = -1;
        int priorityMask = -1;
        boolean navPath = false;
        boolean navProgress = false;
        double navValue = -1.0;
        if (entity instanceof Mob mob) {
            hasTarget = mob.getTarget() != null;
            noAi = mob.isNoAi();
            brainMask = 0;
            brainMask |= mob.getBrain().isActive(Activity.CORE) ? 1 : 0;
            brainMask |= mob.getBrain().isActive(Activity.IDLE) ? 2 : 0;
            brainMask |= mob.getBrain().isActive(Activity.WORK) ? 4 : 0;
            brainMask |= mob.getBrain().isActive(Activity.PLAY) ? 8 : 0;
            brainMask |= mob.getBrain().isActive(Activity.REST) ? 16 : 0;
            brainMask |= mob.getBrain().isActive(Activity.FIGHT) ? 32 : 0;
            brainMask |= mob.getBrain().isActive(Activity.PANIC) ? 64 : 0;
            goalMask = 0;
            priorityMask = 0;
            int running = 0;
            for (net.minecraft.world.entity.ai.goal.WrappedGoal goal : mob.goalSelector
                .getAvailableGoals()) {
                int priority = goal.getPriority();
                priorityMask |= 1 << Math.min(30, priority);
                if (goal.isRunning()) {
                    running++;
                    goalMask |= 1 << Math.min(30, running);
                }
            }
            navPath = mob.getNavigation().getPath() != null;
            navProgress = mob.getNavigation().isInProgress();
            navValue = navPath ? 1.0 : 0.0;
            groups.add("MOB");
            groups.add("BRAIN");
            groups.add("GOAL");
            groups.add("NAVIGATION");
        }
        return new EntityRowFields(removed, passenger, tickCount, health, maxHealth, air, maxAir,
            hasTarget, noAi, brainMask, goalMask, priorityMask, -1, -1, navPath, navProgress,
            navValue, groups);
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

    /** The internal dormant property of the production move gate; it is not a configuration key and
     * every shipped build leaves it off, so the write-back passes a null gate by default. */
    public static final String MOVE_GATE_PROPERTY = "arclight.prts.moveGate";

    private static volatile OwnerContext ownerContext;

    /**
     * The move admission the production write-back passes, or null while the dormant property is
     * off. When it is on, one row asks the move authority for its state, section membership and
     * directory rights before the setter runs; the facts are read here on the tick thread, so no
     * worker asks a world for an entity.
     */
    public static BatchWriteBack.MoveGate writeBackMoveGate() {
        if (!Boolean.getBoolean(MOVE_GATE_PROPERTY)) {
            return null;
        }
        OwnerContext context = ownerContext;
        if (context == null) {
            return null;
        }
        context.versions().refresh(true);
        return new EntityMoveGate(context, EntityMoveGate.LIVE_FACTS,
            (worldId, entityId) -> {
                Entity entity = entity(level(worldId), entityId);
                return entity == null ? null : new EntityMoveGate.Position(entity.getX(),
                    entity.getY(), entity.getZ());
            })::admit;
    }

    /** Binds the process owner context the dormant move gate reads; no injection means no gate. */
    public static void bindOwnerContext(OwnerContext context) {
        ownerContext = context;
    }

    /** The write-back face of this access: the gate reads the live identity and revision, the row
     * write is the one this class already performs. */
    public static BatchWriteBack.LiveAccess writeBack() {
        return new BatchWriteBack.LiveAccess() {

            @Override
            public EntityWriteBackGate.LiveRow read(String worldId, int entityId) {
                ServerLevel level = level(worldId);
                Entity entity = entity(level, entityId);
                if (entity == null) {
                    return null;
                }
                long uuidHigh = entity.getUUID().getMostSignificantBits();
                long uuidLow = entity.getUUID().getLeastSignificantBits();
                return new EntityWriteBackGate.LiveRow(uuidHigh, uuidLow,
                    EntityObjectGenerations.shared().generationOf(worldId, uuidHigh, uuidLow),
                    entity.tickCount);
            }

            @Override
            public void write(String worldId, int entityId, double x, double y, double z, double yaw,
                              double pitch, double velX, double velY, double velZ) {
                Entity entity = entity(level(worldId), entityId);
                if (entity != null) {
                    LiveEntityAccess.write(entity, x, y, z, yaw, pitch, velX, velY, velZ);
                }
            }

            @Override
            public boolean identical(String worldId, int entityId, StateHasher.Slice row) {
                Entity entity = entity(level(worldId), entityId);
                return entity != null && LiveEntityAccess.identical(entity, row);
            }
        };
    }

    /** The bit-exact kinematic comparison the takeover boundary rests on. */
    public static final class KinematicIdentity {

        private KinematicIdentity() {
        }

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

        public static boolean sameDouble(double left, double right) {
            return Double.doubleToRawLongBits(left) == Double.doubleToRawLongBits(right);
        }

        public static boolean sameFloat(float left, float right) {
            return Float.floatToRawIntBits(left) == Float.floatToRawIntBits(right);
        }
    }
}
