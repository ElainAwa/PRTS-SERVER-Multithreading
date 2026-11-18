/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The eligibility predicate of the ownership fixture: which rows a whole-tick skip may be offered
 * for. The bar is a whole-tick model that reproduces every transition of the row, so only classes
 * that have one pass; every other row is refused with the reason that names what a model of it
 * would have to read. It reads host state only and answers with the first reason that applies.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityQueryReadings;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import io.izzel.arclight.common.prts.support.EntityEligibility;
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

    // Refusal reasons, first match wins: player, foreign class, removed, riding or leashed, an
    // active AI, item/projectile callback, class without a model, fire, fluid, pushable neighbours,
    // a model that refuses the row, and finally the widened row.
    static final int WIDENED = EntityEligibility.Takeover.WIDENED;
    static final int THIRD_PARTY = EntityEligibility.Takeover.THIRD_PARTY;
    static final int PLAYER = EntityEligibility.Takeover.PLAYER;
    static final int LIFECYCLE = EntityEligibility.Takeover.LIFECYCLE;
    static final int RIDING = EntityEligibility.Takeover.RIDING;
    static final int AI = EntityEligibility.Takeover.AI;
    static final int CALLBACK = EntityEligibility.Takeover.CALLBACK;
    static final int UNMODELED = EntityEligibility.Takeover.UNMODELED;
    static final int NEIGHBOURS = EntityEligibility.Takeover.NEIGHBOURS;
    static final int FLUID = EntityEligibility.Takeover.FLUID;
    static final int FIRE = EntityEligibility.Takeover.FIRE;
    static final int MODEL = EntityEligibility.Takeover.MODEL;

    /** How many refusal reasons the table above declares. */
    static final int REASONS = EntityEligibility.Takeover.COUNT;

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
        boolean player = entity instanceof Player;
        boolean foreignClass = !entity.getClass().getName().startsWith("net.minecraft.");
        boolean removed = entity.isRemoved();
        boolean riding = entity.isPassenger() || entity.isVehicle()
            || (entity instanceof Leashable leashable && leashable.isLeashed());
        // A row that runs its own AI is refused before any model is asked: a whole-tick model of a
        // mob row reproduces the path of an inactive AI only, so it cannot answer for an active one.
        boolean aiMob = entity instanceof Mob mob && !mob.isNoAi();
        boolean itemOrProjectile = entity instanceof ItemEntity || entity instanceof Projectile;
        EntityEligibility.Reason early = EntityEligibility.classify(EntityEligibility.observedFacts(
            player, foreignClass, removed, riding, aiMob, itemOrProjectile,
            EntityEligibility.Model.UNREAD, EntityEligibility.Fact.UNREAD,
            EntityEligibility.Fact.UNREAD, EntityEligibility.Fact.UNREAD));
        if (early != EntityEligibility.Reason.ELIGIBLE) {
            return projection(early);
        }
        // Only a row the early facts leave open asks the model registry and the world; a fire, a
        // fluid or a missing model is decided before the neighbour query, exactly as before.
        WholeTickModel model = EntityModelRegistry.modelOf(entity);
        EntityEligibility.Model modelFact = model == null
            ? EntityEligibility.Model.NONE : EntityEligibility.Model.REPLICABLE;
        boolean fire = entity.isOnFire();
        boolean fluid = entity.isInWater() || entity.isInLava() || entity.isInPowderSnow;
        EntityEligibility.Fact neighbours = EntityEligibility.Fact.UNREAD;
        boolean pushedNeighbour = false;
        boolean neighbourRead = false;
        if (model != null && !fire && !fluid) {
            // The inflated box is a superset of the exact box the eligibility fact needs: one scan
            // answers both when it is empty, and only a non-empty inflated box asks for the exact
            // box as well. The model then reuses this verdict instead of scanning again.
            EntityQueryReadings.noteQuery();
            pushedNeighbour = !level.getEntities(entity, entity.getBoundingBox().inflate(0.5D),
                EntitySelector.pushableBy(entity)).isEmpty();
            neighbourRead = true;
            if (pushedNeighbour) {
                EntityQueryReadings.noteQuery();
                neighbours = level.getEntities(entity, entity.getBoundingBox(),
                    EntitySelector.pushableBy(entity)).isEmpty()
                    ? EntityEligibility.Fact.NO : EntityEligibility.Fact.YES;
            } else {
                neighbours = EntityEligibility.Fact.NO;
            }
        }
        EntityEligibility.Reason reason = EntityEligibility.classify(
            EntityEligibility.observedFacts(player, foreignClass, removed, riding, aiMob,
                itemOrProjectile, modelFact, read(fire), read(fluid), neighbours));
        if (reason == EntityEligibility.Reason.ELIGIBLE && model != null) {
            int refusal = neighbourRead ? model.refusal(entity, pushedNeighbour)
                : model.refusal(entity);
            if (refusal != WholeTickModel.REPLICABLE) {
                model.noteRefusal(refusal);
                reason = EntityEligibility.Reason.MODEL;
            }
        }
        return projection(reason);
    }

    /** The code of one reason in this predicate's code set; a declared widen keeps its old effect. */
    private static int projection(EntityEligibility.Reason reason) {
        int code = EntityEligibility.Takeover.of(reason);
        return code == MODEL && FaultInjection.ownershipWidens() ? WIDENED : code;
    }

    /** One boolean row fact. */
    private static EntityEligibility.Fact read(boolean value) {
        return value ? EntityEligibility.Fact.YES : EntityEligibility.Fact.NO;
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
