/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one armour stand row. It reproduces the state transitions of
 * Entity#tick / LivingEntity#tick / LivingEntity#aiStep / ArmorStand#tick for the rows the
 * predicate below admits, and it refuses every row whose tick would take a branch that reads the
 * world: such a branch cannot be decided on a worker thread, so the row stays with the host.
 *
 * <p>The model never calls into the entity from a worker: the plan point captures a state on the
 * tick thread, the worker computes on that copy, and the host entry applies the answer before the
 * host would have ticked the row. The position is not written, because no admitted row moves.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import io.izzel.arclight.common.prts.fixes.ownership.PrtsCombatTrackerStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsEntityTickStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsLivingEntityTickStateMixin;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.LongAdder;

/**
 * The whole-tick model of a no-physics armour stand. All entry points run on the tick thread except
 * {@link #compute}, which reads and writes nothing but the state it is given.
 */
public final class ArmorStandTick {

    /** The row's whole tick is reproduced by this model. */
    public static final int REPLICABLE = 0;
    /** The row has physics: its travel reads block collisions, friction and fall damage. */
    public static final int PHYSICS = 1;
    /** A field the model pins is not in its pinned value. */
    public static final int STATE = 2;
    /** The row carries equipment, whose enchantments tick inside the entity tick. */
    public static final int EQUIPMENT = 3;
    /** The row carries mob effects, whose tick can write the row and the world. */
    public static final int EFFECTS = 4;
    /** The row's combat tracker holds damage state that its recheck can rewrite. */
    public static final int COMBAT = 5;
    /** The row is in a portal, or its portal cooldown has not run out. */
    public static final int PORTAL = 6;
    /** The row's eye is in a fluid, or the row is in rain or a bubble column. */
    public static final int FLUID = 7;
    /** How many refusal conditions the model declares; the evidence counters are indexed by them. */
    public static final int REFUSALS = 8;

    private static final LongAdder[] REFUSAL_COUNTS = counters();
    private static final LongAdder COMPUTE_NANOS = new LongAdder();
    private static final LongAdder COMPUTE_ROWS = new LongAdder();

    private ArmorStandTick() {
    }

    /** The first condition that keeps this row out of the ownership set, or {@link #REPLICABLE}. */
    public static int refusal(ArmorStand stand) {
        // A row with physics runs LivingEntity#travel, and that call decides motion from block
        // collisions, the friction of the block below and fall damage. A worker may not read the
        // world, so those rows are out of the model rather than approximated.
        if (!stand.isMarker() && !stand.isNoGravity()) {
            return PHYSICS;
        }
        if (!stand.noPhysics) {
            return PHYSICS;
        }
        if (stand.isRemoved() || stand.isPassenger() || stand.isVehicle() || !stand.isAlive()
            || stand.isDeadOrDying()) {
            return STATE;
        }
        if (stand.isOnFire() || stand.getRemainingFireTicks() != 0 || stand.getTicksFrozen() != 0) {
            return STATE;
        }
        // A row in rain only reaches the fire branch of the base tick, and that branch writes
        // nothing while the remaining fire ticks are zero, so rain is not a refusal of its own.
        if (stand.isInWater() || stand.isInLava() || stand.isInPowderSnow
            || stand.isEyeInFluid(FluidTags.WATER) || stand.isEyeInFluid(FluidTags.LAVA)) {
            return FLUID;
        }
        // checkBelowWorld removes the row; the model does not reproduce removal.
        if (stand.getY() < (double) (stand.level().getMinBuildHeight() - 64)) {
            return STATE;
        }
        if (stand.getAirSupply() != stand.getMaxAirSupply()) {
            return STATE;
        }
        if (stand.isSprinting() || stand.isSwimming() || stand.isFallFlying() || stand.isSleeping()
            || stand.isUsingItem() || stand.isVisuallySwimming()
            || stand.getPose() != Pose.STANDING) {
            return STATE;
        }
        if (!stand.getActiveEffects().isEmpty()) {
            return EFFECTS;
        }
        if (stand.getArrowCount() != 0 || stand.getStingerCount() != 0) {
            return STATE;
        }
        if (combat(stand)) {
            return COMBAT;
        }
        if (!equipmentEmpty(stand)) {
            return EQUIPMENT;
        }
        if (stand.getScale() != 1.0F || stand.getSwimAmount(1.0F) != 0.0F
            || stand.getSwimAmount(0.0F) != 0.0F) {
            return STATE;
        }
        PrtsEntityTickStateMixin entityState = entityState(stand);
        if (stand.getPortalCooldown() != 0 || entityState.prts$portalProcess() != null
            || stand.level().getBlockState(stand.blockPosition()).getBlock() instanceof Portal) {
            return PORTAL;
        }
        PrtsLivingEntityTickStateMixin livingState = livingState(stand);
        if (entityState.prts$firstTick() || entityState.prts$boardingCooldown() != 0
            || entityState.prts$wasEyeInWater() || entityState.prts$portalProcess() != null) {
            return STATE;
        }
        if (livingState.prts$effectsDirty() || livingState.prts$noJumpDelay() != 0
            || livingState.prts$jumping() || livingState.prts$lerpSteps() != 0
            || livingState.prts$lerpHeadSteps() != 0
            || livingState.prts$autoSpinAttackTicks() != 0 || livingState.prts$fallFlyTicks() != 0
            || livingState.prts$swimAmount() != 0.0F || livingState.prts$swimAmountO() != 0.0F
            || livingState.prts$attackAnim() != 0.0F
            || livingState.prts$appliedScale() != stand.getScale()
            || livingState.prts$lastHurtByPlayerTime() != 0
            || livingState.prts$lastHurtByPlayer() != null
            || stand.getLastHurtMob() != null || stand.getLastHurtByMob() != null
            || stand.hurtTime != 0 || stand.invulnerableTime != 0 || stand.deathTime != 0) {
            return STATE;
        }
        // The armour stand pushes the rideable minecarts within 0.2 blocks of its centre, and the
        // living push walks everything pushable that overlaps it. A pushable entity within half a
        // block is inside the inflated box of the row, so this one query refuses both cases.
        if (!stand.level().getEntities(stand, stand.getBoundingBox().inflate(0.5D),
            EntitySelector.pushableBy(stand)).isEmpty()) {
            return STATE;
        }
        return REPLICABLE;
    }

    /** Reads every field the model needs; the caller runs on the tick thread and owns the row. */
    public static void capture(ArmorStand stand, TickState state) {
        state.clear();
        PrtsEntityTickStateMixin entityState = entityState(stand);
        PrtsLivingEntityTickStateMixin livingState = livingState(stand);
        state.entityId = stand.getId();
        // The host raises the tick counter and writes the previous position right before the host
        // entry of this row; the plan point runs before that, so it predicts what the host will
        // write there, and the host entry rechecks the prediction against the live row.
        state.tickCount = stand.tickCount + 1;
        state.x = stand.getX();
        state.y = stand.getY();
        state.z = stand.getZ();
        state.xo = state.x;
        state.yo = state.y;
        state.zo = state.z;
        state.yRot = stand.getYRot();
        state.xRot = stand.getXRot();
        state.yRotO = stand.yRotO;
        state.xRotO = stand.xRotO;
        state.yBodyRot = stand.yBodyRot;
        state.yBodyRotO = stand.yBodyRotO;
        state.yHeadRot = stand.yHeadRot;
        state.yHeadRotO = stand.yHeadRotO;
        Vec3 motion = stand.getDeltaMovement();
        state.vx = motion.x;
        state.vy = motion.y;
        state.vz = motion.z;
        state.xxa = stand.xxa;
        state.yya = stand.yya;
        state.zza = stand.zza;
        state.walkDist = stand.walkDist;
        state.walkDistO = stand.walkDistO;
        state.animStep = livingState.prts$animStep();
        state.animStepO = livingState.prts$animStepO();
        state.oAttackAnim = stand.oAttackAnim;
        state.attackAnim = stand.attackAnim;
        state.run = livingState.prts$run();
        state.oRun = livingState.prts$oRun();
        state.onGround = stand.onGround();
        state.horizontalCollision = stand.horizontalCollision;
        state.verticalCollision = stand.verticalCollision;
        state.verticalCollisionBelow = stand.verticalCollisionBelow;
        state.isInPowderSnow = stand.isInPowderSnow;
        state.wasInPowderSnow = stand.wasInPowderSnow;
        state.wasEyeInWater = entityState.prts$wasEyeInWater();
        state.hurtTime = stand.hurtTime;
        state.invulnerableTime = stand.invulnerableTime;
        state.deathTime = stand.deathTime;
        state.remainingFireTicks = stand.getRemainingFireTicks();
        state.ticksFrozen = stand.getTicksFrozen();
        state.airSupply = stand.getAirSupply();
        state.boardingCooldown = entityState.prts$boardingCooldown();
        state.noJumpDelay = livingState.prts$noJumpDelay();
        state.lerpSteps = livingState.prts$lerpSteps();
        state.lerpHeadSteps = livingState.prts$lerpHeadSteps();
        state.autoSpinAttackTicks = livingState.prts$autoSpinAttackTicks();
        state.fallFlyTicks = livingState.prts$fallFlyTicks();
        state.jumping = livingState.prts$jumping();
        state.firstTick = entityState.prts$firstTick();
        state.effectsDirty = livingState.prts$effectsDirty();
        state.takingDamage = combat(stand);
        state.swimAmount = livingState.prts$swimAmount();
        state.swimAmountO = livingState.prts$swimAmountO();
        state.appliedScale = livingState.prts$appliedScale();
        state.portalCooldown = stand.getPortalCooldown();
        state.inPortal = stand.level().getBlockState(stand.blockPosition()).getBlock()
            instanceof Portal;
        state.lastPosMoved = !stand.blockPosition().equals(livingState.prts$lastPos());
    }

    /**
     * The whole tick of an admitted row, as pure arithmetic on the captured state. A false return
     * is a row the model will not answer for; the host then runs the row itself.
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
        state.walkDistO = state.walkDist;
        state.xRotO = state.xRot;
        state.yRotO = state.yRot;
        state.wasInPowderSnow = state.isInPowderSnow;
        state.isInPowderSnow = false;
        // LivingEntity#baseTick tail: the same lags are written again, and the body and head lags
        // take the current rotation.
        state.animStepO = state.animStep;
        state.yBodyRotO = state.yBodyRot;
        state.yHeadRotO = state.yHeadRot;
        state.yRotO = state.yRot;
        state.xRotO = state.xRot;
        // LivingEntity#aiStep: a row whose effective AI is off damps its motion, then the small
        // components are zeroed. ArmorStand#isEffectiveAi is false whenever hasPhysics is false.
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
        // The jump block of aiStep damps the local movement input and resets the jump delay.
        state.xxa *= 0.98F;
        state.zza *= 0.98F;
        state.noJumpDelay = 0;
        // LivingEntity#travel is skipped: the row is not controlled by a local instance, and
        // ArmorStand#travel itself does nothing while hasPhysics is false.
        // The walk and run bookkeeping of LivingEntity#tick.
        double dx = state.x - state.xo;
        double dz = state.z - state.zo;
        float squared = (float) (dx * dx + dz * dz);
        float heading = state.yBodyRot;
        float moving = 0.0F;
        state.oRun = state.run;
        if (squared > 0.0025000002F) {
            moving = 1.0F;
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
        // ArmorStand#tickHeadTurn copies the yaw lag and the yaw onto the body, and returns zero,
        // so the distance walked by the animation does not advance this tick.
        state.yBodyRotO = state.yRotO;
        state.yBodyRot = state.yRot;
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
        return true;
    }

    /** Writes the answer of one row back onto the row; the host entry runs it on the tick thread. */
    public static void apply(ArmorStand stand, TickState state) {
        PrtsEntityTickStateMixin entityState = entityState(stand);
        PrtsLivingEntityTickStateMixin livingState = livingState(stand);
        stand.yRotO = state.yRotO;
        stand.xRotO = state.xRotO;
        stand.yBodyRot = state.yBodyRot;
        stand.yBodyRotO = state.yBodyRotO;
        stand.yHeadRotO = state.yHeadRotO;
        stand.walkDistO = state.walkDistO;
        stand.wasInPowderSnow = state.wasInPowderSnow;
        stand.isInPowderSnow = state.isInPowderSnow;
        stand.setDeltaMovement(state.vx, state.vy, state.vz);
        stand.xxa = state.xxa;
        stand.zza = state.zza;
        stand.oAttackAnim = state.oAttackAnim;
        livingState.prts$setAnimStepO(state.animStepO);
        livingState.prts$setRun(state.run);
        livingState.prts$setORun(state.oRun);
        entityState.prts$setInBlockState(null);
        if (state.lastPosMoved) {
            livingState.prts$setLastPos(stand.blockPosition());
        }
    }

    /** The refusal counters of the model, indexed by the conditions above. */
    public static LongAdder[] refusalCounts() {
        return REFUSAL_COUNTS;
    }

    /** Clears the refusal and cost counters; the readout reset uses it. */
    public static void reset() {
        for (LongAdder counter : REFUSAL_COUNTS) {
            counter.reset();
        }
        COMPUTE_NANOS.reset();
        COMPUTE_ROWS.reset();
    }

    /** Records the cost of one worker answer; observation only, the wait is spent outside it. */
    public static void noteCompute(long nanos) {
        COMPUTE_NANOS.add(nanos);
        COMPUTE_ROWS.increment();
    }

    /** Nanoseconds the workers spent in the model. */
    public static long computeNanos() {
        return COMPUTE_NANOS.sum();
    }

    /** Rows the workers answered with the model. */
    public static long computeRows() {
        return COMPUTE_ROWS.sum();
    }

    /** Counts one refusal by the row that was offered. */
    public static void noteRefusal(int reason) {
        if (reason > 0 && reason < REFUSALS) {
            REFUSAL_COUNTS[reason].increment();
        }
    }

    private static boolean combat(ArmorStand stand) {
        return ((PrtsCombatTrackerStateMixin) (Object) stand.getCombatTracker()).prts$takingDamage();
    }

    private static boolean equipmentEmpty(ArmorStand stand) {
        for (ItemStack stack : stand.getAllSlots()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static PrtsEntityTickStateMixin entityState(Entity entity) {
        return (PrtsEntityTickStateMixin) (Object) entity;
    }

    private static PrtsLivingEntityTickStateMixin livingState(Entity entity) {
        return (PrtsLivingEntityTickStateMixin) (Object) entity;
    }

    private static LongAdder[] counters() {
        LongAdder[] counters = new LongAdder[REFUSALS];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = new LongAdder();
        }
        return counters;
    }
}
