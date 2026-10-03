/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The state of one row as the whole-tick model reads and writes it: the fields a no-physics armour
 * stand tick changes, plus the witnesses the model needs to know which branches that tick takes.
 * A captured state is written by the plan point on the tick thread, read by a worker, and applied
 * back by the host entry; no field of it is shared between two rows.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

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
    }
}
