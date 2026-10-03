/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The eligibility predicate of the ownership fixture: which rows a whole-tick skip may be offered
 * for. The bar is a whole-tick model that reproduces every transition of the row, so only classes
 * that have one pass; every other row is refused with the reason that names what a model of it
 * would have to read. It reads host state only and answers with the first reason that applies.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/** The narrowed predicate: a row is eligible when no reason below applies, and the first reason
 * that does apply is the verdict. */
final class OwnershipEligibility {

    // Refusal reasons, first match wins: player, foreign class, removed, riding or leashed, AI,
    // item/projectile callback, class without a model, pushable neighbours, fluid, fire, or a
    // state the row's own model does not cover.
    static final int WIDENED = 0;
    static final int THIRD_PARTY = 1;
    static final int PLAYER = 2;
    static final int LIFECYCLE = 3;
    static final int RIDING = 4;
    static final int AI = 5;
    static final int CALLBACK = 6;
    static final int UNMODELED = 7;
    static final int NEIGHBOURS = 8;
    static final int FLUID = 9;
    static final int FIRE = 10;
    static final int MODEL = 11;

    /** How many refusal reasons the table above declares. */
    static final int REASONS = 12;

    /** Fingerprint bits the host entry compares between the plan point and the tick. */
    static final byte F_REMOVED = 1;
    static final byte F_RIDING = 1 << 1;
    static final byte F_FIRE = 1 << 2;
    static final byte F_FLUID = 1 << 3;
    static final byte F_AI = 1 << 4;
    static final byte F_MODEL = 1 << 5;

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
        WholeTickModel model = TickModels.of(entity);
        if (model == null) {
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
        int refusal = model.refusal(entity);
        if (refusal == WholeTickModel.REPLICABLE) {
            return WIDENED;
        }
        model.noteRefusal(refusal);
        // A declared widen directive admits a refused row on purpose: the negative fixture.
        return FaultInjection.ownershipWidens() ? WIDENED : MODEL;
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
        WholeTickModel model = TickModels.of(entity);
        if (model == null || !model.retains(entity)) {
            bits |= F_MODEL;
        }
        return bits;
    }
}
