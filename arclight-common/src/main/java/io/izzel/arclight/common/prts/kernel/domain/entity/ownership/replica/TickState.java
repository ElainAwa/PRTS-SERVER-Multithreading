/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The state of one row as the whole-tick model reads and writes it: the fields a no-physics armour
 * stand tick changes, plus the witnesses the model needs to know which branches that tick takes.
 * A captured state is written by the plan point on the tick thread, read by a worker, and applied
 * back by the host entry; no field of it is shared between two rows.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import net.minecraft.core.BlockPos;

/** The frozen state of one row of one tick. */
public final class TickState {

    /** Identity of the row; the host entry addresses the lease by it. */
    public int entityId;
    /** The tick counter the host raised before it fired the entity tick event. */
    public int tickCount;
    /** Position; the model of a no-physics row never changes it. */
    public double x;
    public double y;
    public double z;
    /** The previous position the host wrote before it fired the entity tick event. */
    public double xo;
    public double yo;
    public double zo;
    /** Rotation and the rotation lags the tick rewrites. */
    public float yRot;
    public float xRot;
    public float yRotO;
    public float xRotO;
    public float yBodyRot;
    public float yBodyRotO;
    public float yHeadRot;
    public float yHeadRotO;
    /** Motion, and the local movement input the tick damps. */
    public double vx;
    public double vy;
    public double vz;
    public float xxa;
    public float yya;
    public float zza;
    /** Distance walked, and the walk animation bookkeeping the tick rewrites. */
    public float walkDist;
    public float walkDistO;
    public float animStep;
    public float animStepO;
    public float oAttackAnim;
    public float attackAnim;
    public float run;
    public float oRun;
    /** Vision state the tick decays. */
    public float swimAmount;
    public float swimAmountO;
    public float appliedScale;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean verticalCollisionBelow;
    public boolean isInPowderSnow;
    public boolean wasInPowderSnow;
    public boolean wasEyeInWater;
    public int hurtTime;
    public int invulnerableTime;
    public int deathTime;
    public int remainingFireTicks;
    public int ticksFrozen;
    public int airSupply;
    public int boardingCooldown;
    public int noJumpDelay;
    public int lerpSteps;
    public int lerpHeadSteps;
    public int autoSpinAttackTicks;
    public int fallFlyTicks;
    public boolean jumping;
    public boolean firstTick;
    public boolean effectsDirty;
    public boolean takingDamage;
    /** Whether the block position of the row moved away from the position the tick last saw. */
    public boolean lastPosMoved;
    /** Whether the row is in a portal block, and its portal cooldown. */
    public boolean inPortal;
    public int portalCooldown;
    /** The block position the base tick last saw; the tick rewrites it when the row moved. */
    public BlockPos lastPos;
    /** The walk animation bookkeeping the entity animation update decays. */
    public float walkSpeedOld;
    public float walkSpeed;
    public float walkPosition;
    /** The body rotation control of a mob: how long the head has been stable, and around which
     * yaw it was stable. The tick of a mob row steps this control. */
    public int bodyHeadStableTime;
    public float bodyLastStableYHeadRot;
    /** The age of an ageable row; its tick walks the age back to zero. */
    public int age;
    /** The unhappy counter of a villager row; its tick decrements it. */
    public int unhappyCounter;
    /** Whether a bat row rests: a resting bat zeroes its motion and snaps onto the block below. */
    public boolean resting;
    /** The head yaw limit of the row's body rotation control. */
    public int headTurnLimit;
    /** Whether the row carries a mob passenger, which the body rotation control checks. */
    public boolean carryingMobPassenger;
    /** Whether the base tick clears the block-state memo of the row. */
    public boolean inBlockStateCleared;
    /** The height of the row's bounding box, which the bat rest snap needs. */
    public float bbHeight;
    /** Whether the tick snapped the row's position, so the commit must repeat that call. */
    public boolean positionSnapped;

    /** Copies another state over this one; the worker answers on a copy of the captured row. */
    public void copyFrom(TickState other) {
        entityId = other.entityId;
        tickCount = other.tickCount;
        x = other.x;
        y = other.y;
        z = other.z;
        xo = other.xo;
        yo = other.yo;
        zo = other.zo;
        yRot = other.yRot;
        xRot = other.xRot;
        yRotO = other.yRotO;
        xRotO = other.xRotO;
        yBodyRot = other.yBodyRot;
        yBodyRotO = other.yBodyRotO;
        yHeadRot = other.yHeadRot;
        yHeadRotO = other.yHeadRotO;
        vx = other.vx;
        vy = other.vy;
        vz = other.vz;
        xxa = other.xxa;
        yya = other.yya;
        zza = other.zza;
        walkDist = other.walkDist;
        walkDistO = other.walkDistO;
        animStep = other.animStep;
        animStepO = other.animStepO;
        oAttackAnim = other.oAttackAnim;
        attackAnim = other.attackAnim;
        run = other.run;
        oRun = other.oRun;
        swimAmount = other.swimAmount;
        swimAmountO = other.swimAmountO;
        appliedScale = other.appliedScale;
        onGround = other.onGround;
        horizontalCollision = other.horizontalCollision;
        verticalCollision = other.verticalCollision;
        verticalCollisionBelow = other.verticalCollisionBelow;
        isInPowderSnow = other.isInPowderSnow;
        wasInPowderSnow = other.wasInPowderSnow;
        wasEyeInWater = other.wasEyeInWater;
        hurtTime = other.hurtTime;
        invulnerableTime = other.invulnerableTime;
        deathTime = other.deathTime;
        remainingFireTicks = other.remainingFireTicks;
        ticksFrozen = other.ticksFrozen;
        airSupply = other.airSupply;
        boardingCooldown = other.boardingCooldown;
        noJumpDelay = other.noJumpDelay;
        lerpSteps = other.lerpSteps;
        lerpHeadSteps = other.lerpHeadSteps;
        autoSpinAttackTicks = other.autoSpinAttackTicks;
        fallFlyTicks = other.fallFlyTicks;
        jumping = other.jumping;
        firstTick = other.firstTick;
        effectsDirty = other.effectsDirty;
        takingDamage = other.takingDamage;
        lastPosMoved = other.lastPosMoved;
        inPortal = other.inPortal;
        portalCooldown = other.portalCooldown;
        lastPos = other.lastPos;
        walkSpeedOld = other.walkSpeedOld;
        walkSpeed = other.walkSpeed;
        walkPosition = other.walkPosition;
        bodyHeadStableTime = other.bodyHeadStableTime;
        bodyLastStableYHeadRot = other.bodyLastStableYHeadRot;
        age = other.age;
        unhappyCounter = other.unhappyCounter;
        resting = other.resting;
        headTurnLimit = other.headTurnLimit;
        carryingMobPassenger = other.carryingMobPassenger;
        inBlockStateCleared = other.inBlockStateCleared;
        bbHeight = other.bbHeight;
        positionSnapped = other.positionSnapped;
    }

    /** Clears every field, so a reused row cannot carry the answer of another row. */
    public void clear() {
        entityId = 0;
        tickCount = 0;
        x = 0.0;
        y = 0.0;
        z = 0.0;
        xo = 0.0;
        yo = 0.0;
        zo = 0.0;
        yRot = 0.0F;
        xRot = 0.0F;
        yRotO = 0.0F;
        xRotO = 0.0F;
        yBodyRot = 0.0F;
        yBodyRotO = 0.0F;
        yHeadRot = 0.0F;
        yHeadRotO = 0.0F;
        vx = 0.0;
        vy = 0.0;
        vz = 0.0;
        xxa = 0.0F;
        yya = 0.0F;
        zza = 0.0F;
        walkDist = 0.0F;
        walkDistO = 0.0F;
        animStep = 0.0F;
        animStepO = 0.0F;
        oAttackAnim = 0.0F;
        attackAnim = 0.0F;
        run = 0.0F;
        oRun = 0.0F;
        swimAmount = 0.0F;
        swimAmountO = 0.0F;
        appliedScale = 0.0F;
        onGround = false;
        horizontalCollision = false;
        verticalCollision = false;
        verticalCollisionBelow = false;
        isInPowderSnow = false;
        wasInPowderSnow = false;
        wasEyeInWater = false;
        hurtTime = 0;
        invulnerableTime = 0;
        deathTime = 0;
        remainingFireTicks = 0;
        ticksFrozen = 0;
        airSupply = 0;
        boardingCooldown = 0;
        noJumpDelay = 0;
        lerpSteps = 0;
        lerpHeadSteps = 0;
        autoSpinAttackTicks = 0;
        fallFlyTicks = 0;
        jumping = false;
        firstTick = false;
        effectsDirty = false;
        takingDamage = false;
        lastPosMoved = false;
        inPortal = false;
        portalCooldown = 0;
        lastPos = null;
        walkSpeedOld = 0.0F;
        walkSpeed = 0.0F;
        walkPosition = 0.0F;
        bodyHeadStableTime = 0;
        bodyLastStableYHeadRot = 0.0F;
        age = 0;
        unhappyCounter = 0;
        resting = false;
        headTurnLimit = 0;
        carryingMobPassenger = false;
        inBlockStateCleared = false;
        bbHeight = 0.0F;
        positionSnapped = false;
    }
}
