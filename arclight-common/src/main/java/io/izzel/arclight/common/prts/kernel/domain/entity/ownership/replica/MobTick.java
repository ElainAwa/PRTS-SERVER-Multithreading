/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick transition shared by the mob classes the model covers: everything a no-AI, dry,
 * unencumbered mob row does from Entity#baseTick through LivingEntity#aiStep and the walk
 * bookkeeping of LivingEntity#tick, including the body rotation control that Mob#tickHeadTurn
 * steps. Each class that has a model adds its own tail on top of this path.
 *
 * <p>Every step here is decided from the frozen row state alone. The steps that read the world or
 * the row's random stream are not taken: the predicate admits only rows whose pinned state makes
 * those steps identity, and the two steps whose input is the row's own random stream or the level
 * clock are committed by the host entry (see commitHostSteps). A row whose state does not match
 * the pins is refused before the model answers for it.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import io.izzel.arclight.common.prts.fixes.ownership.PrtsAttributeMapStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsBodyRotationControlStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsCombatTrackerStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsEntityTickStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsLivingEntityTickStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsMobTickStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsWalkAnimationStateMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

/**
 * The shared whole-tick path of a pinned mob row. All entry points run on the tick thread except
 * {@link #compute}, which reads and writes nothing but the state it is given.
 */
public final class MobTick {

    /** The row's whole tick is reproduced by the model of its class. */
    public static final int REPLICABLE = 0;
    /** A field the model pins is not in its pinned value. */
    public static final int STATE = 1;
    /** The row is in a fluid, in rain or in a bubble column. */
    public static final int FLUID = 2;
    /** The row is on fire or frozen. */
    public static final int FIRE = 3;
    /** The row carries mob effects, whose tick can write the row and consume its random stream. */
    public static final int EFFECTS = 4;
    /** The row carries equipment, whose enchantments tick inside the entity tick. */
    public static final int EQUIPMENT = 5;
    /** The row's combat tracker holds damage state that its recheck can rewrite. */
    public static final int COMBAT = 6;
    /** The row is in a portal, or its portal cooldown has not run out. */
    public static final int PORTAL = 7;
    /** The row can pick up loot, so its tick queries the world for item entities. */
    public static final int PICKUP = 8;
    /** How many refusal conditions this path declares. */
    public static final int REFUSALS = 9;

    private static final String[] REFUSAL_NAMES = {
        "replicable", "state", "fluid", "fire", "effects", "equipment", "combat", "portal", "pickup"
    };

    private MobTick() {
    }

    /** The name of one refusal reason; the models print it in their evidence lines. */
    public static String refusalName(int reason) {
        return reason > 0 && reason < REFUSAL_NAMES.length ? REFUSAL_NAMES[reason] : "unknown";
    }

    /** The names of the refusal reasons, indexed the same way as the counters. */
    public static String[] refusalNames() {
        return REFUSAL_NAMES.clone();
    }

    /** The first condition that keeps this mob row out of the model, or {@link #REPLICABLE}. */
    public static int refusal(LivingEntity living) {
        if (living.isRemoved() || living.isPassenger() || living.isVehicle() || !living.isAlive()
            || living.isDeadOrDying()) {
            return STATE;
        }
        if (living.isOnFire() || living.getRemainingFireTicks() != 0
            || living.getTicksFrozen() != 0) {
            return FIRE;
        }
        // The fluid, rain, bubble and eye updates of the base tick decide motion and the fire
        // branch from world state; a dry row takes the identity path through all of them.
        if (living.isInWater() || living.isInLava() || living.isInPowderSnow
            || living.isEyeInFluid(FluidTags.WATER) || living.isEyeInFluid(FluidTags.LAVA)
            || living.isInWaterRainOrBubble()) {
            return FLUID;
        }
        // checkBelowWorld removes the row, and isInWall can hurt it; neither is reproduced.
        if (living.getY() < (double) (living.level().getMinBuildHeight() - 64)
            || living.isInWall()) {
            return STATE;
        }
        if (living.getAirSupply() != living.getMaxAirSupply()) {
            return STATE;
        }
        if (living.isSprinting() || living.isSwimming() || living.isFallFlying()
            || living.isSleeping() || living.isUsingItem() || living.isVisuallySwimming()
            || living.getPose() != Pose.STANDING) {
            return STATE;
        }
        if (!living.getActiveEffects().isEmpty()) {
            return EFFECTS;
        }
        if (living.getArrowCount() != 0 || living.getStingerCount() != 0) {
            return STATE;
        }
        if (((PrtsCombatTrackerStateMixin) (Object) living.getCombatTracker()).prts$takingDamage()) {
            return COMBAT;
        }
        if (!equipmentEmpty(living)) {
            return EQUIPMENT;
        }
        if (living.getScale() != 1.0F || living.getSwimAmount(1.0F) != 0.0F
            || living.getSwimAmount(0.0F) != 0.0F) {
            return STATE;
        }
        PrtsEntityTickStateMixin entityState = entityState(living);
        if (living.getPortalCooldown() != 0 || entityState.prts$portalProcess() != null
            || living.level().getBlockState(living.blockPosition()).getBlock() instanceof Portal) {
            return PORTAL;
        }
        if (entityState.prts$firstTick() || entityState.prts$boardingCooldown() != 0
            || entityState.prts$wasEyeInWater()) {
            return STATE;
        }
        // The base tick writes the fire flag from the visual-fire field, and the leash tick can
        // re-attach a row that still holds leash data; neither write is reproduced.
        if (living.hasVisualFire) {
            return STATE;
        }
        if (living instanceof Leashable leashable && leashable.getLeashData() != null) {
            return STATE;
        }
        PrtsLivingEntityTickStateMixin livingState = livingState(living);
        if (livingState.prts$effectsDirty() || livingState.prts$noJumpDelay() != 0
            || livingState.prts$jumping() || livingState.prts$lerpSteps() != 0
            || livingState.prts$lerpHeadSteps() != 0
            || livingState.prts$autoSpinAttackTicks() != 0 || livingState.prts$fallFlyTicks() != 0
            || livingState.prts$swimAmount() != 0.0F || livingState.prts$swimAmountO() != 0.0F
            || livingState.prts$attackAnim() != 0.0F
            || livingState.prts$appliedScale() != living.getScale()
            || livingState.prts$lastHurtByPlayerTime() != 0
            || livingState.prts$lastHurtByPlayer() != null
            || living.getLastHurtMob() != null || living.getLastHurtByMob() != null
            || living.hurtTime != 0 || living.invulnerableTime != 0 || living.deathTime != 0) {
            return STATE;
        }
        // A pending attribute update would make refreshDirtyAttributes write the row.
        if (!attributeState(living).prts$pendingAttributes().isEmpty()) {
            return STATE;
        }
        // A pickup-capable mob asks the world for item entities inside its reach every tick. The
        // query is not reproduced by a worker, so a row with an item inside that reach stays with
        // the host: the box is wider than the reach the tick uses, so passing it means the tick
        // sees the same empty list and its pickup branch is a no-op either way.
        if (living instanceof Mob mob && mob.canPickUpLoot()
            && !living.level().getEntitiesOfClass(ItemEntity.class,
            living.getBoundingBox().inflate(1.0, 1.0, 1.0)).isEmpty()) {
            return PICKUP;
        }
        return REPLICABLE;
    }

    /** Reads every field the shared path needs; the caller runs on the tick thread. */
    public static void capture(Entity entity, TickState state) {
        state.clear();
        LivingEntity living = (LivingEntity) entity;
        PrtsEntityTickStateMixin entityState = entityState(entity);
        PrtsLivingEntityTickStateMixin livingState = livingState(entity);
        state.entityId = entity.getId();
        // The host raises the tick counter and writes the previous position right before the host
        // entry of this row; the plan point runs before that, so it predicts what the host will
        // write there, and the host entry rechecks the prediction against the live row.
        state.tickCount = entity.tickCount + 1;
        state.x = entity.getX();
        state.y = entity.getY();
        state.z = entity.getZ();
        state.xo = state.x;
        state.yo = state.y;
        state.zo = state.z;
        state.yRot = entity.getYRot();
        state.xRot = entity.getXRot();
        state.yRotO = entity.yRotO;
        state.xRotO = entity.xRotO;
        state.yBodyRot = living.yBodyRot;
        state.yBodyRotO = living.yBodyRotO;
        state.yHeadRot = living.yHeadRot;
        state.yHeadRotO = living.yHeadRotO;
        Vec3 motion = entity.getDeltaMovement();
        state.vx = motion.x;
        state.vy = motion.y;
        state.vz = motion.z;
        state.xxa = living.xxa;
        state.yya = living.yya;
        state.zza = living.zza;
        state.walkDist = living.walkDist;
        state.walkDistO = living.walkDistO;
        state.animStep = livingState.prts$animStep();
        state.animStepO = livingState.prts$animStepO();
        state.oAttackAnim = living.oAttackAnim;
        state.attackAnim = living.attackAnim;
        state.run = livingState.prts$run();
        state.oRun = livingState.prts$oRun();
        state.onGround = entity.onGround();
        state.horizontalCollision = entity.horizontalCollision;
        state.verticalCollision = entity.verticalCollision;
        state.verticalCollisionBelow = entity.verticalCollisionBelow;
        state.isInPowderSnow = entity.isInPowderSnow;
        state.wasInPowderSnow = entity.wasInPowderSnow;
        state.wasEyeInWater = entityState.prts$wasEyeInWater();
        state.hurtTime = living.hurtTime;
        state.invulnerableTime = living.invulnerableTime;
        state.deathTime = living.deathTime;
        state.remainingFireTicks = entity.getRemainingFireTicks();
        state.ticksFrozen = entity.getTicksFrozen();
        state.airSupply = entity.getAirSupply();
        state.boardingCooldown = entityState.prts$boardingCooldown();
        state.noJumpDelay = livingState.prts$noJumpDelay();
        state.lerpSteps = livingState.prts$lerpSteps();
        state.lerpHeadSteps = livingState.prts$lerpHeadSteps();
        state.autoSpinAttackTicks = livingState.prts$autoSpinAttackTicks();
        state.fallFlyTicks = livingState.prts$fallFlyTicks();
        state.jumping = livingState.prts$jumping();
        state.firstTick = entityState.prts$firstTick();
        state.effectsDirty = livingState.prts$effectsDirty();
        state.takingDamage = ((PrtsCombatTrackerStateMixin) (Object) living.getCombatTracker())
            .prts$takingDamage();
        state.swimAmount = livingState.prts$swimAmount();
        state.swimAmountO = livingState.prts$swimAmountO();
        state.appliedScale = livingState.prts$appliedScale();
        state.portalCooldown = entity.getPortalCooldown();
        state.inPortal = entity.level().getBlockState(entity.blockPosition()).getBlock()
            instanceof Portal;
        state.lastPos = livingState.prts$lastPos();
        state.lastPosMoved = false;
        PrtsWalkAnimationStateMixin animation = animationState(entity);
        state.walkSpeedOld = animation.prts$speedOld();
        state.walkSpeed = animation.prts$speed();
        state.walkPosition = animation.prts$position();
        PrtsBodyRotationControlStateMixin control = bodyControl((Mob) entity);
        state.bodyHeadStableTime = control.prts$headStableTime();
        state.bodyLastStableYHeadRot = control.prts$lastStableYHeadRot();
        state.headTurnLimit = ((Mob) entity).getMaxHeadYRot();
        state.carryingMobPassenger = entity.getFirstPassenger() instanceof Mob;
    }

    /**
     * The shared transition of one pinned mob row, as pure arithmetic on the captured state. A
     * false return is a row the model will not answer for; the host then runs the row itself.
     */
    public static boolean compute(TickState state) {
        if (state.firstTick || state.boardingCooldown != 0 || state.wasEyeInWater
            || state.effectsDirty || state.takingDamage || state.inPortal
            || state.portalCooldown != 0 || state.remainingFireTicks != 0
            || state.ticksFrozen != 0 || state.noJumpDelay != 0
            || state.jumping || state.lerpSteps != 0 || state.lerpHeadSteps != 0
            || state.autoSpinAttackTicks != 0 || state.fallFlyTicks != 0
            || state.swimAmount != 0.0F || state.swimAmountO != 0.0F
            || state.attackAnim != 0.0F || state.appliedScale != 1.0F
            || state.hurtTime != 0 || state.invulnerableTime != 0 || state.deathTime != 0
            || state.isInPowderSnow) {
            return false;
        }
        // LivingEntity#baseTick: the attack animation lag is copied before anything else runs.
        state.oAttackAnim = state.attackAnim;
        // Entity#baseTick: the walk distance lag and the rotation lags, then the powder snow pair.
        state.inBlockStateCleared = true;
        state.walkDistO = state.walkDist;
        state.xRotO = state.xRot;
        state.yRotO = state.yRot;
        state.wasInPowderSnow = state.isInPowderSnow;
        state.isInPowderSnow = false;
        // LivingEntity#baseTick: the block position memo is rewritten when the row left it. The
        // effects a changed position would run are empty while the row carries no equipment.
        BlockPos now = BlockPos.containing(state.x, state.y, state.z);
        if (!now.equals(state.lastPos)) {
            state.lastPos = now;
            state.lastPosMoved = true;
        }
        // LivingEntity#baseTick tail: the lags are written again, and the body and head lags take
        // the current rotation.
        state.animStepO = state.animStep;
        state.yBodyRotO = state.yBodyRot;
        state.yHeadRotO = state.yHeadRot;
        state.yRotO = state.yRot;
        state.xRotO = state.xRot;
        // LivingEntity#tick: updateSwimAmount decays the swim amount; the row is pinned to zero.
        state.swimAmountO = state.swimAmount;
        // LivingEntity#aiStep: a row whose effective AI is off damps its motion, then the small
        // components are zeroed. Mob#isEffectiveAi is false while isNoAi is set.
        state.vx *= 0.98;
        state.vy *= 0.98;
        state.vz *= 0.98;
        double vx = state.vx;
        double vy = state.vy;
        double vz = state.vz;
        if (Math.abs(vx) < 0.003) {
            vx = 0.0;
        }
        if (Math.abs(vy) < 0.003) {
            vy = 0.0;
        }
        if (Math.abs(vz) < 0.003) {
            vz = 0.0;
        }
        state.vx = vx;
        state.vy = vy;
        state.vz = vz;
        // The jump block of aiStep: a row that does not jump only resets its jump delay, and the
        // local movement input is damped. isImmobile is false while the row is alive.
        state.noJumpDelay = 0;
        state.xxa *= 0.98F;
        state.zza *= 0.98F;
        // LivingEntity#travel skips its whole body while the row is not controlled locally; the
        // only call left is the entity animation update, which only walks the animation state.
        float animationInput = (float) Math.min(
            Mth.length(state.x - state.xo, 0.0, state.z - state.zo) * 4.0, 1.0);
        state.walkSpeedOld = state.walkSpeed;
        state.walkSpeed = state.walkSpeed + (animationInput - state.walkSpeed) * 0.4F;
        state.walkPosition = state.walkPosition + state.walkSpeed;
        // The walk and run bookkeeping of LivingEntity#tick.
        double dx = state.x - state.xo;
        double dz = state.z - state.zo;
        float squared = (float) (dx * dx + dz * dz);
        float heading = state.yBodyRot;
        float animStep = 0.0F;
        state.oRun = state.run;
        float moving = 0.0F;
        if (squared > 0.0025000002F) {
            moving = 1.0F;
            animStep = (float) Math.sqrt(squared) * 3.0F;
            float direction = (float) Mth.atan2(dz, dx) * (180.0F / (float) Math.PI) - 90.0F;
            float off = Mth.abs(Mth.wrapDegrees(state.yRot) - direction);
            heading = 95.0F < off && off < 265.0F ? direction - 180.0F : direction;
        }
        if (state.attackAnim > 0.0F) {
            heading = state.yRot;
        }
        if (!state.onGround) {
            moving = 0.0F;
        }
        state.run = state.run + (moving - state.run) * 0.3F;
        bodyRotation(state);
        while (state.yRot - state.yRotO < -180.0F) {
            state.yRotO -= 360.0F;
        }
        while (state.yRot - state.yRotO >= 180.0F) {
            state.yRotO += 360.0F;
        }
        while (state.yBodyRot - state.yBodyRotO < -180.0F) {
            state.yBodyRotO -= 360.0F;
        }
        while (state.yBodyRot - state.yBodyRotO >= 180.0F) {
            state.yBodyRotO += 360.0F;
        }
        while (state.xRot - state.xRotO < -180.0F) {
            state.xRotO -= 360.0F;
        }
        while (state.xRot - state.xRotO >= 180.0F) {
            state.xRotO += 360.0F;
        }
        while (state.yHeadRot - state.yHeadRotO < -180.0F) {
            state.yHeadRotO -= 360.0F;
        }
        while (state.yHeadRot - state.yHeadRotO >= 180.0F) {
            state.yHeadRotO += 360.0F;
        }
        state.animStep += animStep;
        // The fall-flying flag stays clear while the row carries no elytra.
        state.fallFlyTicks = 0;
        return true;
    }

    /** Mob#tickHeadTurn steps the body rotation control; its control state is part of the row. */
    private static void bodyRotation(TickState state) {
        double dx = state.x - state.xo;
        double dz = state.z - state.zo;
        boolean isMoving = dx * dx + dz * dz > 2.5000003E-7F;
        if (isMoving) {
            state.yBodyRot = state.yRot;
            state.yHeadRot = Mth.rotateIfNecessary(state.yHeadRot, state.yBodyRot,
                (float) state.headTurnLimit);
            state.bodyLastStableYHeadRot = state.yHeadRot;
            state.bodyHeadStableTime = 0;
            return;
        }
        if (state.carryingMobPassenger) {
            return;
        }
        if (Math.abs(state.yHeadRot - state.bodyLastStableYHeadRot) > 15.0F) {
            state.bodyHeadStableTime = 0;
            state.bodyLastStableYHeadRot = state.yHeadRot;
            state.yBodyRot = Mth.rotateIfNecessary(state.yBodyRot, state.yHeadRot,
                (float) state.headTurnLimit);
        } else {
            state.bodyHeadStableTime++;
            if (state.bodyHeadStableTime > 10) {
                int elapsed = state.bodyHeadStableTime - 10;
                float part = Mth.clamp((float) elapsed / 10.0F, 0.0F, 1.0F);
                float limit = (float) state.headTurnLimit * (1.0F - part);
                state.yBodyRot = Mth.rotateIfNecessary(state.yBodyRot, state.yHeadRot, limit);
            }
        }
    }

    /** Writes the answer of the shared path back onto the row; the host entry runs it. */
    public static void apply(Entity entity, TickState state) {
        LivingEntity living = (LivingEntity) entity;
        PrtsEntityTickStateMixin entityState = entityState(entity);
        PrtsLivingEntityTickStateMixin livingState = livingState(entity);
        living.yRotO = state.yRotO;
        living.xRotO = state.xRotO;
        living.yBodyRot = state.yBodyRot;
        living.yBodyRotO = state.yBodyRotO;
        living.yHeadRot = state.yHeadRot;
        living.yHeadRotO = state.yHeadRotO;
        living.walkDistO = state.walkDistO;
        living.wasInPowderSnow = state.wasInPowderSnow;
        living.isInPowderSnow = state.isInPowderSnow;
        living.setDeltaMovement(state.vx, state.vy, state.vz);
        living.xxa = state.xxa;
        living.zza = state.zza;
        living.oAttackAnim = state.oAttackAnim;
        livingState.prts$setAnimStepO(state.animStepO);
        livingState.prts$setAnimStep(state.animStep);
        livingState.prts$setRun(state.run);
        livingState.prts$setORun(state.oRun);
        entityState.prts$setInBlockState(null);
        PrtsWalkAnimationStateMixin animation = animationState(entity);
        animation.prts$setSpeedOld(state.walkSpeedOld);
        animation.prts$setSpeed(state.walkSpeed);
        animation.prts$setPosition(state.walkPosition);
        PrtsBodyRotationControlStateMixin control = bodyControl((Mob) entity);
        control.prts$setHeadStableTime(state.bodyHeadStableTime);
        control.prts$setLastStableYHeadRot(state.bodyLastStableYHeadRot);
        if (state.lastPosMoved) {
            livingState.prts$setLastPos(state.lastPos);
        }
    }

    /** The part of the tick that needs the row's own random stream or the level clock. */
    public static void commitHostSteps(Entity entity) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        // Mob#baseTick tail: the ambient sound draw always consumes one value of the row's random
        // stream, and the counter is post-incremented whether or not the draw is under it. The
        // draw stays here because the stream belongs to the live row and the model must not
        // consume it twice; a row the host runs instead never reaches this call.
        boolean ambient = mob.isAlive() && mob.getRandom().nextInt(1000) < mob.ambientSoundTime++;
        if (ambient) {
            mob.ambientSoundTime = -mob.getAmbientSoundInterval();
            mob.playAmbientSound();
        }
        // Mob#tick tail: the goal control flags are recomputed every fifth tick. They stay inert
        // because the row runs no goals at all, and they are written on the live row all the same.
        if (!mob.level().isClientSide && mob.tickCount % 5 == 0) {
            boolean movable = !(mob.getControllingPassenger() instanceof Mob);
            boolean jumpable = !(mob.getVehicle() instanceof Boat);
            PrtsMobTickStateMixin state = (PrtsMobTickStateMixin) (Object) mob;
            state.prts$goalSelector().setControlFlag(Goal.Flag.MOVE, movable);
            state.prts$goalSelector().setControlFlag(Goal.Flag.JUMP, movable && jumpable);
            state.prts$goalSelector().setControlFlag(Goal.Flag.LOOK, movable);
        }
    }

    private static boolean equipmentEmpty(Entity entity) {
        for (ItemStack stack : ((net.minecraft.world.entity.LivingEntity) entity).getAllSlots()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static PrtsEntityTickStateMixin entityState(Entity entity) {
        return (PrtsEntityTickStateMixin) (Object) entity;
    }

    static PrtsLivingEntityTickStateMixin livingState(Entity entity) {
        return (PrtsLivingEntityTickStateMixin) (Object) entity;
    }

    static PrtsMobTickStateMixin mobState(Mob mob) {
        return (PrtsMobTickStateMixin) (Object) mob;
    }

    static PrtsWalkAnimationStateMixin animationState(Entity entity) {
        return (PrtsWalkAnimationStateMixin) (Object) ((LivingEntity) entity).walkAnimation;
    }

    static PrtsBodyRotationControlStateMixin bodyControl(Mob mob) {
        return (PrtsBodyRotationControlStateMixin) (Object) mobState(mob).prts$bodyRotationControl();
    }

    static PrtsAttributeMapStateMixin attributeState(Entity entity) {
        return (PrtsAttributeMapStateMixin) (Object) ((LivingEntity) entity).getAttributes();
    }
}
