/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The eligibility predicate of the ownership fixture: which rows a whole-tick skip may be offered
 * for at all. It reads host state only, and it answers with the first reason that refuses a row,
 * so a refusal is attributable. The fingerprint is the part of the verdict the host entry can
 * recheck without touching the world.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.GlowSquid;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * The narrowed predicate of the ownership fixture. A row is eligible when none of the reasons
 * below apply; the reasons are ordered, and the first one that applies is the verdict.
 */
final class OwnershipEligibility {

    /** The whole tick of this row is offered to the ownership set. */
    static final int WIDENED = 0;
    /** The class is not the host's: owning it would own foreign code. */
    static final int THIRD_PARTY = 1;
    /** A player row is never an ownership row. */
    static final int PLAYER = 2;
    /** The row is being removed. */
    static final int LIFECYCLE = 3;
    /** The row rides, is ridden or is leashed: its motion belongs to another entity. */
    static final int RIDING = 4;
    /** The row runs goal selectors, navigation or sensing. */
    static final int AI = 5;
    /** The row runs an item, projectile or platform callback inside its tick. */
    static final int CALLBACK = 6;
    /** No whole-tick model is stated for this class. */
    static final int UNMODELED = 7;
    /** The row has pushable neighbours, whose motion it shares. */
    static final int NEIGHBOURS = 8;
    /** The row sits in a fluid whose state decides its motion. */
    static final int FLUID = 9;
    /** The row is on fire. */
    static final int FIRE = 10;

    /** How many refusal reasons the table above declares. */
    static final int REASONS = 11;

    /** The row is gone; the token must not be exercised. */
    static final byte F_REMOVED = 1;
    /** The row became a passenger or a vehicle. */
    static final byte F_RIDING = 1 << 1;
    /** The row caught fire. */
    static final byte F_FIRE = 1 << 2;
    /** The row entered a fluid. */
    static final byte F_FLUID = 1 << 3;
    /** The row started running its AI. */
    static final byte F_AI = 1 << 4;

    private OwnershipEligibility() {
    }

    /** The first reason this row is refused, or {@link #WIDENED} when none applies. */
    static int reasonOf(ServerLevel level, Entity entity) {
        if (entity instanceof Player) {
            return PLAYER;
        }
        if (!entity.getClass().getName().startsWith("net.minecraft.")) {
            return THIRD_PARTY;
        }
        if (entity.isRemoved()) {
            return LIFECYCLE;
        }
        if (entity.isPassenger() || entity.isVehicle()
            || (entity instanceof Leashable leashable && leashable.isLeashed())) {
            return RIDING;
        }
        if (entity instanceof Mob mob && !mob.isNoAi()) {
            return AI;
        }
        if (entity instanceof ItemEntity || entity instanceof Projectile) {
            return CALLBACK;
        }
        if (!modeled(entity)) {
            return UNMODELED;
        }
        if (entity.isOnFire()) {
            return FIRE;
        }
        if (entity.isInWater() || entity.isInLava() || entity.isInPowderSnow) {
            return FLUID;
        }
        if (!level.getEntities(entity, entity.getBoundingBox(), EntitySelector.pushableBy(entity))
            .isEmpty()) {
            return NEIGHBOURS;
        }
        return WIDENED;
    }

    /** The part of the verdict the host entry can recheck without a world query; zero for a row
     * the predicate admitted at the plan point. */
    static byte fingerprintOf(Entity entity) {
        byte bits = 0;
        if (entity.isRemoved()) {
            bits |= F_REMOVED;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            bits |= F_RIDING;
        }
        if (entity.isOnFire()) {
            bits |= F_FIRE;
        }
        if (entity.isInWater() || entity.isInLava() || entity.isInPowderSnow) {
            bits |= F_FLUID;
        }
        if (entity instanceof Mob mob && !mob.isNoAi()) {
            bits |= F_AI;
        }
        return bits;
    }

    /** Whether a whole tick of this row has a model the fixture is willing to own. */
    private static boolean modeled(Entity entity) {
        if (entity instanceof Villager || entity instanceof Squid || entity instanceof GlowSquid
            || entity instanceof Bat || entity instanceof ArmorStand || entity instanceof Marker) {
            return true;
        }
        // A falling block that rests on the ground writes a block and can raise a callback; only
        // the airborne row has a tick that reads the world without writing it.
        return entity instanceof FallingBlockEntity && !entity.onGround();
    }
}
