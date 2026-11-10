/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Runtime trait view of one tickable entity. It records capabilities and the methods a rank owns,
 * so a model can be chosen by what the class actually does instead of by a class-name allowlist.
 * The view is read-only; it never changes a tick decision.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import java.lang.reflect.Method;

/** Trait bits and method-profile facts for one entity class. */
public final class EntityTraitClassifier {

    public static final long T_BASE = 1L;
    public static final long T_LIVING = 1L << 1;
    public static final long T_MOB = 1L << 2;
    public static final long T_AI_ON = 1L << 3;
    public static final long T_AI_OFF = 1L << 4;
    public static final long T_PATHFINDER = 1L << 5;
    public static final long T_PROJECTILE = 1L << 6;
    public static final long T_ITEM = 1L << 7;
    public static final long T_EXP_ORB = 1L << 8;
    public static final long T_VEHICLE = 1L << 9;
    public static final long T_PASSENGER = 1L << 10;
    public static final long T_ARMOR_STAND = 1L << 11;
    public static final long T_MARKER = 1L << 12;
    public static final long T_AEC = 1L << 13;
    public static final long T_TNT = 1L << 14;
    public static final long T_FALLING = 1L << 15;
    public static final long T_DISPLAY = 1L << 16;
    public static final long T_SPECIAL_TICK = 1L << 17;
    public static final long T_MODDED = 1L << 18;
    public static final long T_C0_PLAYER = 1L << 19;

    public static final int M_ENTITY_TICK = 1;
    public static final int M_LIVING_TICK = 1 << 1;
    public static final int M_SERVER_AI = 1 << 2;
    public static final int M_TRAVEL = 1 << 3;
    public static final int M_RIDE_TICK = 1 << 4;
    public static final int M_PROJECTILE_TICK = 1 << 5;

    /** Facts supplied by the class-level test seam; production fills them from instanceof. */
    public record Facts(boolean living, boolean mob, boolean aiOn, boolean pathfinder,
                        boolean projectile, boolean item, boolean experienceOrb, boolean vehicle,
                        boolean passenger, boolean marker, boolean areaEffectCloud,
                        boolean armorStand, boolean primedTnt, boolean fallingBlock,
                        boolean display, boolean player) {
    }

    /** What the classifier reads: capabilities plus the methods this class declares. */
    public record Profile(long traits, int methodMask, String className, String modId) {
    }

    private EntityTraitClassifier() {
    }

    /** Production classification; reads only the live row's class, flags and registry key. */
    public static Profile classify(Entity entity) {
        boolean player = entity instanceof Player;
        Facts facts = new Facts(entity instanceof LivingEntity, entity instanceof Mob,
            entity instanceof Mob mob && !mob.isNoAi(), entity instanceof PathfinderMob,
            entity instanceof Projectile, entity instanceof ItemEntity,
            entity instanceof ExperienceOrb, entity instanceof VehicleEntity,
            entity.isPassenger(), entity instanceof Marker, entity instanceof AreaEffectCloud,
            entity instanceof ArmorStand, entity instanceof PrimedTnt,
            entity instanceof FallingBlockEntity, entity instanceof Display || entity instanceof Interaction,
            player);
        String modId;
        try {
            modId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace();
        } catch (RuntimeException failed) {
            modId = "unknown";
        }
        return classify(entity.getClass(), facts, modId);
    }

    /** Class-level classification; used by tests and by the production wrapper. */
    public static Profile classify(Class<?> type, Facts facts, String modId) {
        long bits = T_BASE;
        if (facts.living()) bits |= T_LIVING;
        if (facts.mob()) bits |= T_MOB;
        if (facts.aiOn()) bits |= T_AI_ON;
        if (facts.mob() && !facts.aiOn()) bits |= T_AI_OFF;
        if (facts.pathfinder()) bits |= T_PATHFINDER;
        if (facts.projectile()) bits |= T_PROJECTILE;
        if (facts.item()) bits |= T_ITEM;
        if (facts.experienceOrb()) bits |= T_EXP_ORB;
        if (facts.vehicle()) bits |= T_VEHICLE;
        if (facts.passenger()) bits |= T_PASSENGER;
        if (facts.marker()) bits |= T_MARKER;
        if (facts.areaEffectCloud()) bits |= T_AEC;
        if (facts.armorStand()) bits |= T_ARMOR_STAND;
        if (facts.primedTnt()) bits |= T_TNT;
        if (facts.fallingBlock()) bits |= T_FALLING;
        if (facts.display()) bits |= T_DISPLAY;
        if (facts.player()) bits |= T_C0_PLAYER;
        if (modId != null && !modId.equals("minecraft")) bits |= T_MODDED;
        int methods = methodMask(type);
        if ((methods & M_ENTITY_TICK) != 0) bits |= T_SPECIAL_TICK;
        return new Profile(bits, methods, type.getName(), modId);
    }

    /** Methods declared below Entity that a model must account for. */
    static int methodMask(Class<?> type) {
        int mask = 0;
        if (overrides(type, "tick")) mask |= M_ENTITY_TICK;
        if (overrides(type, "serverAiStep")) mask |= M_SERVER_AI;
        if (overrides(type, "travel")) mask |= M_TRAVEL;
        if (overrides(type, "rideTick")) mask |= M_RIDE_TICK;
        if (overrides(type, "onHit")) mask |= M_PROJECTILE_TICK;
        return mask;
    }

    private static boolean overrides(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
