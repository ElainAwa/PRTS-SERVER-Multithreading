/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one area effect cloud row. It reproduces the state transitions the server
 * branch of the cloud tick takes on a row whose contents carry no effects: the base tick lags, the
 * wait-flag transition around the wait time, the radius step and the life check. A row whose life
 * runs out, whose radius would fall under the floor or whose contents do carry effects is refused
 * inside the model, so it runs on the host instead of being approximated.
 *
 * <p>Only {@link #compute} runs off the tick thread, and it reads and writes nothing but the state
 * it is given. The server branch is selected by the level, not by the row: a dedicated server never
 * runs the client branch that emits particles.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import io.izzel.arclight.common.prts.fixes.ownership.PrtsAreaEffectCloudStateMixin;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsEntityTickStateMixin;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Portal;

import java.util.concurrent.atomic.LongAdder;

/** The whole-tick model of an effect-free area effect cloud. */
public final class AreaEffectCloudTick implements WholeTickModel {

    /** The one model instance of this class. */
    public static final WholeTickModel MODEL = new AreaEffectCloudTick();

    /** The row's whole tick is reproduced by this model. */
    public static final int REPLICABLE = 0;
    /** A field the model pins is not in its pinned value. */
    public static final int STATE = 1;
    /** The contents carry effects, whose application queries the entities inside the cloud. */
    public static final int EFFECTS = 2;
    /** The row is in a fluid, whose state decides the base tick. */
    public static final int FLUID = 3;
    /** The row is in a portal block, or its portal cooldown has not run out. */
    public static final int PORTAL = 4;
    /** How many refusal conditions the model declares. */
    public static final int REFUSALS = 5;

    private static final LongAdder[] REFUSAL_COUNTS = counters();
    private static final LongAdder COMPUTE_NANOS = new LongAdder();
    private static final LongAdder COMPUTE_ROWS = new LongAdder();
    private static final LongAdder APPLIED = new LongAdder();
    private static final String[] REFUSAL_NAMES = {
        "replicable", "state", "effects", "fluid", "portal"
    };

    private AreaEffectCloudTick() {
    }

    @Override
    public String name() {
        return "area_effect_cloud";
    }

    @Override
    public int refusal(Entity entity) {
        AreaEffectCloud cloud = (AreaEffectCloud) entity;
        PrtsAreaEffectCloudStateMixin state = cloudState(cloud);
        if (cloud.isRemoved() || cloud.isPassenger() || cloud.isVehicle() || !cloud.isAlive()) {
            return STATE;
        }
        // A cloud with effects queries every living entity inside its box every fifth tick and can
        // write those entities; the model does not answer for that branch.
        if (state.prts$potionContents().hasEffects()) {
            return EFFECTS;
        }
        if (cloud.isOnFire() || cloud.getRemainingFireTicks() != 0
            || cloud.getTicksFrozen() != 0) {
            return STATE;
        }
        if (cloud.isInWater() || cloud.isInLava() || cloud.isInPowderSnow
            || cloud.isEyeInFluid(FluidTags.WATER) || cloud.isEyeInFluid(FluidTags.LAVA)) {
            return FLUID;
        }
        if (cloud.getY() < (double) (cloud.level().getMinBuildHeight() - 64)) {
            return STATE;
        }
        if (cloud.getPose() != Pose.STANDING) {
            return STATE;
        }
        PrtsEntityTickStateMixin entityState = entityState(cloud);
        if (entityState.prts$firstTick() || entityState.prts$boardingCooldown() != 0
            || entityState.prts$wasEyeInWater() || entityState.prts$portalProcess() != null
            || cloud.getPortalCooldown() != 0
            || cloud.level().getBlockState(cloud.blockPosition()).getBlock() instanceof Portal) {
            return PORTAL;
        }
        return REPLICABLE;
    }

    @Override
    public void capture(Entity entity, TickState state) {
        AreaEffectCloud cloud = (AreaEffectCloud) entity;
        PrtsAreaEffectCloudStateMixin cloudState = cloudState(cloud);
        PrtsEntityTickStateMixin entityState = entityState(cloud);
        state.clear();
        state.entityId = cloud.getId();
        // The host raises the tick counter and writes the previous position right before the host
        // entry, so the plan point predicts both and the host entry rechecks the prediction.
        state.tickCount = cloud.tickCount + 1;
        state.x = cloud.getX();
        state.y = cloud.getY();
        state.z = cloud.getZ();
        state.yRot = cloud.getYRot();
        state.xRot = cloud.getXRot();
        state.yRotO = cloud.yRotO;
        state.xRotO = cloud.xRotO;
        state.walkDist = cloud.walkDist;
        state.walkDistO = cloud.walkDistO;
        state.isInPowderSnow = cloud.isInPowderSnow;
        state.wasInPowderSnow = cloud.wasInPowderSnow;
        state.remainingFireTicks = cloud.getRemainingFireTicks();
        state.ticksFrozen = cloud.getTicksFrozen();
        state.boardingCooldown = entityState.prts$boardingCooldown();
        state.firstTick = entityState.prts$firstTick();
        state.wasEyeInWater = entityState.prts$wasEyeInWater();
        state.portalCooldown = cloud.getPortalCooldown();
        state.inPortal = cloud.level().getBlockState(cloud.blockPosition()).getBlock()
            instanceof Portal;
        state.waiting = cloud.isWaiting();
        state.radius = cloud.getRadius();
        state.radiusPerTick = cloudState.prts$radiusPerTick();
        state.waitTime = cloudState.prts$waitTime();
        state.duration = cloudState.prts$duration();
    }

    /** The whole tick of an admitted row, as pure arithmetic on the captured state. */
    @Override
    public boolean compute(TickState state) {
        if (state.firstTick || state.boardingCooldown != 0 || state.wasEyeInWater
            || state.remainingFireTicks != 0 || state.ticksFrozen != 0
            || state.isInPowderSnow || state.inPortal || state.portalCooldown != 0) {
            return false;
        }
        // Entity#baseTick: the walk-distance lag and the rotation lags, then the powder snow pair.
        state.walkDistO = state.walkDist;
        state.xRotO = state.xRot;
        state.yRotO = state.yRot;
        state.wasInPowderSnow = state.isInPowderSnow;
        state.isInPowderSnow = false;
        // AreaEffectCloud#tick, server branch. The client branch emits particles and never runs on
        // a dedicated server.
        if (state.tickCount >= state.waitTime + state.duration) {
            // The tick discards the row; the model refuses and the host runs it.
            return false;
        }
        boolean waiting = state.tickCount < state.waitTime;
        state.waitingChanged = waiting != state.waiting;
        state.waiting = waiting;
        if (waiting) {
            return true;
        }
        if (state.radiusPerTick != 0.0F) {
            float radius = state.radius + state.radiusPerTick;
            if (radius < 0.5F) {
                // The tick discards the row; the model refuses and the host runs it.
                return false;
            }
            // The data write clamps the radius, so the next tick reads the clamped value.
            state.radius = Mth.clamp(radius, 0.0F, 32.0F);
            state.radiusStepped = true;
        }
        // Every fifth tick the victims of an effect-free cloud are cleared; the map is empty
        // because only the effect branch below ever fills it.
        return true;
    }

    @Override
    public void apply(Entity entity, TickState state) {
        AreaEffectCloud cloud = (AreaEffectCloud) entity;
        cloud.yRotO = state.yRotO;
        cloud.xRotO = state.xRotO;
        cloud.walkDistO = state.walkDistO;
        cloud.wasInPowderSnow = state.wasInPowderSnow;
        cloud.isInPowderSnow = state.isInPowderSnow;
        entityState(cloud).prts$setInBlockState(null);
        if (state.radiusStepped) {
            cloud.setRadius(state.radius);
        }
        if (state.waitingChanged) {
            cloudState(cloud).prts$setWaiting(state.waiting);
        }
    }

    @Override
    public void commitHostSteps(Entity entity, TickState state) {
    }

    /** Whether the row is still in a state the model covers, judged without a world query. */
    @Override
    public boolean retains(Entity entity) {
        return !((AreaEffectCloud) entity).isRemoved()
            && !cloudState((AreaEffectCloud) entity).prts$potionContents().hasEffects();
    }

    @Override
    public void noteCompute(long nanos) {
        COMPUTE_NANOS.add(nanos);
        COMPUTE_ROWS.increment();
    }

    @Override
    public long computeNanos() {
        return COMPUTE_NANOS.sum();
    }

    @Override
    public long computeRows() {
        return COMPUTE_ROWS.sum();
    }

    @Override
    public void noteApplied() {
        APPLIED.increment();
    }

    @Override
    public long appliedCount() {
        return APPLIED.sum();
    }

    @Override
    public void noteRefusal(int reason) {
        if (reason > 0 && reason < REFUSAL_COUNTS.length) {
            REFUSAL_COUNTS[reason].increment();
        }
    }

    @Override
    public LongAdder[] refusalCounts() {
        return REFUSAL_COUNTS;
    }

    @Override
    public String[] refusalNames() {
        return REFUSAL_NAMES.clone();
    }

    @Override
    public void reset() {
        for (LongAdder counter : REFUSAL_COUNTS) {
            counter.reset();
        }
        COMPUTE_NANOS.reset();
        COMPUTE_ROWS.reset();
        APPLIED.reset();
    }

    private static PrtsEntityTickStateMixin entityState(Entity entity) {
        return (PrtsEntityTickStateMixin) (Object) entity;
    }

    private static PrtsAreaEffectCloudStateMixin cloudState(AreaEffectCloud cloud) {
        return (PrtsAreaEffectCloudStateMixin) (Object) cloud;
    }

    private static LongAdder[] counters() {
        LongAdder[] counters = new LongAdder[REFUSALS];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = new LongAdder();
        }
        return counters;
    }
}
