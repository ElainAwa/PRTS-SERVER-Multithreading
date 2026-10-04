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

    /** The slot of a row whose class no model covers; such a row is never claimed. */
    public static final int OUTSIDE = -1;
    /** How many classes have a whole-tick model; the slots are the order of {@link #all()}. */
    public static final int SLOTS = 5;
    public static final int ARMOR_STAND_SLOT = 0;
    public static final int VILLAGER_SLOT = 1;
    public static final int BAT_SLOT = 2;
    public static final int MARKER_SLOT = 3;
    public static final int AREA_EFFECT_CLOUD_SLOT = 4;

    private static final String[] ARMOR_STAND_REFUSALS = {
        "replicable", "physics", "state", "equipment", "effects", "combat", "portal", "fluid"
    };
    private static final WholeTickModel ARMOR_STAND = new ArmorStandModel();
    private static final List<WholeTickModel> ALL = List.of(ARMOR_STAND, VillagerTick.MODEL,
        BatTick.MODEL, MarkerTick.MODEL, AreaEffectCloudTick.MODEL);

    private TickModels() {
    }

    /** The slot of this row's class on the whitelist, or {@link #OUTSIDE} for every other class.
     * The slot names both the model that answers for the row and the bucket the evidence reports. */
    public static int slotOf(Entity entity) {
        Class<?> type = entity.getClass();
        if (type == ArmorStand.class) {
            return ARMOR_STAND_SLOT;
        }
        if (type == Villager.class) {
            return VILLAGER_SLOT;
        }
        if (type == Bat.class) {
            return BAT_SLOT;
        }
        if (type == Marker.class) {
            return MARKER_SLOT;
        }
        if (type == AreaEffectCloud.class) {
            return AREA_EFFECT_CLOUD_SLOT;
        }
        return OUTSIDE;
    }

    /** The model of this row's class, or null when no model covers the class. */
    public static WholeTickModel of(Entity entity) {
        int slot = slotOf(entity);
        return slot == OUTSIDE ? null : ALL.get(slot);
    }

    /** The name of one slot, for the evidence line of the census. */
    public static String nameOf(int slot) {
        return slot >= 0 && slot < ALL.size() ? ALL.get(slot).name() : "outside";
    }

    /** The class the model of one slot answers for; the class census prices a class with it. */
    public static Class<?> classOf(int slot) {
        return switch (slot) {
            case ARMOR_STAND_SLOT -> ArmorStand.class;
            case VILLAGER_SLOT -> Villager.class;
            case BAT_SLOT -> Bat.class;
            case MARKER_SLOT -> Marker.class;
            case AREA_EFFECT_CLOUD_SLOT -> AreaEffectCloud.class;
            default -> null;
        };
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
