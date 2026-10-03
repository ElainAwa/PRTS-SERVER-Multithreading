/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The registry of whole-tick models: which class a row belongs to, and which model answers for it.
 * A class without a model is refused by the predicate, never approximated; the armour stand model
 * keeps its own cost and refusal counters and is adapted here so every model presents one shape.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.Villager;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class TickModels {

    private static final String[] ARMOR_STAND_REFUSALS = {
        "replicable", "physics", "state", "equipment", "effects", "combat", "portal", "fluid"
    };
    private static final WholeTickModel ARMOR_STAND = new ArmorStandModel();
    private static final List<WholeTickModel> ALL = List.of(ARMOR_STAND, VillagerTick.MODEL,
        BatTick.MODEL, MarkerTick.MODEL, AreaEffectCloudTick.MODEL);

    private TickModels() {
    }

    /** The model of this row's class, or null when no model covers the class. */
    public static WholeTickModel of(Entity entity) {
        if (entity.getClass() == ArmorStand.class) {
            return ARMOR_STAND;
        }
        if (entity.getClass() == Villager.class) {
            return VillagerTick.MODEL;
        }
        if (entity.getClass() == Bat.class) {
            return BatTick.MODEL;
        }
        if (entity.getClass() == Marker.class) {
            return MarkerTick.MODEL;
        }
        if (entity.getClass() == AreaEffectCloud.class) {
            return AreaEffectCloudTick.MODEL;
        }
        return null;
    }

    /** Every model, for the evidence line and the readout reset. */
    public static List<WholeTickModel> all() {
        return ALL;
    }

    /** The armour stand model; the package tests drive the lease with it. */
    public static WholeTickModel armorStand() {
        return ARMOR_STAND;
    }

    /** The armour stand model behind the shared interface; it answers for a no-physics stand. */
    private static final class ArmorStandModel implements WholeTickModel {

        private final LongAdder applied = new LongAdder();

        @Override
        public String name() {
            return "armor_stand";
        }

        @Override
        public boolean retains(Entity entity) {
            ArmorStand stand = (ArmorStand) entity;
            return stand.noPhysics && (stand.isMarker() || stand.isNoGravity());
        }

        @Override
        public int refusal(Entity entity) {
            return ArmorStandTick.refusal((ArmorStand) entity);
        }

        @Override
        public void capture(Entity entity, TickState state) {
            ArmorStandTick.capture((ArmorStand) entity, state);
        }

        @Override
        public boolean compute(TickState state) {
            return ArmorStandTick.compute(state);
        }

        @Override
        public void apply(Entity entity, TickState state) {
            ArmorStandTick.apply((ArmorStand) entity, state);
        }

        @Override
        public void commitHostSteps(Entity entity, TickState state) {
            // The armour stand path has no step whose input is a random stream or the level clock.
        }

        @Override
        public void noteRefusal(int reason) {
            ArmorStandTick.noteRefusal(reason);
        }

        @Override
        public LongAdder[] refusalCounts() {
            return ArmorStandTick.refusalCounts();
        }

        @Override
        public String[] refusalNames() {
            return ARMOR_STAND_REFUSALS.clone();
        }

        @Override
        public void noteCompute(long nanos) {
            ArmorStandTick.noteCompute(nanos);
        }

        @Override
        public long computeNanos() {
            return ArmorStandTick.computeNanos();
        }

        @Override
        public long computeRows() {
            return ArmorStandTick.computeRows();
        }

        @Override
        public void noteApplied() {
            applied.increment();
        }

        @Override
        public long appliedCount() {
            return applied.sum();
        }

        @Override
        public void reset() {
            applied.reset();
            ArmorStandTick.reset();
        }
    }
}
