/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The production support table: every class it declares names its traits and its one per-class
 * program, the five special classes are only a declared subset of the supported set, and any class
 * the table does not know - a modded or third-party one included - resolves to an explicit C1 host
 * fallback that is never counted as supported. Installing the table registers the trait composite
 * with the model registry; nothing installs it by default and no eligibility changes without it.
 */
public final class EntitySupportMatrix {

    private static final String MINECRAFT = "minecraft";

    private static final List<EntitySupportProfile> DECLARED = List.of(
        profile("Mob", Set.of(EntityTrait.TICK, EntityTrait.LIVING, EntityTrait.MOB,
            EntityTrait.BRAIN, EntityTrait.GOAL, EntityTrait.SENSOR, EntityTrait.NAVIGATION,
            EntityTrait.MOVE_CONTROL), EntitySupportProfile.SupportKind.SUPPORTED),
        profile("LivingEntity", Set.of(EntityTrait.TICK, EntityTrait.LIVING),
            EntitySupportProfile.SupportKind.SUPPORTED),
        profile("Entity", Set.of(EntityTrait.TICK),
            EntitySupportProfile.SupportKind.SUPPORTED),
        subset("ArmorStand", Set.of(EntityTrait.TICK, EntityTrait.LIVING)),
        subset("Villager", Set.of(EntityTrait.TICK, EntityTrait.LIVING, EntityTrait.MOB,
            EntityTrait.BRAIN, EntityTrait.SENSOR, EntityTrait.NAVIGATION, EntityTrait.MOVE_CONTROL)),
        subset("Bat", Set.of(EntityTrait.TICK, EntityTrait.LIVING, EntityTrait.MOB,
            EntityTrait.GOAL, EntityTrait.NAVIGATION, EntityTrait.MOVE_CONTROL)),
        subset("Marker", Set.of(EntityTrait.TICK)),
        subset("AreaEffectCloud", Set.of(EntityTrait.TICK)));

    private static final Map<String, EntitySupportProfile> BY_CLASS = index(DECLARED);
    private static final EntitySupportProfile FALLBACK = new EntitySupportProfile("unknown",
        MINECRAFT, EntitySupportProfile.SupportKind.C1_FALLBACK, Set.of(), "base");

    private EntitySupportMatrix() {
    }

    /** The declared profiles, supported and special subset only. */
    public static List<EntitySupportProfile> declared() {
        return DECLARED;
    }

    /** The profile of one class; an unknown class or a foreign mod is the explicit C1 fallback. */
    public static EntitySupportProfile profileFor(String className, String modId) {
        Objects.requireNonNull(className, "className");
        String mod = modId == null ? MINECRAFT : modId;
        if (!MINECRAFT.equals(mod) && !"neoforge".equals(mod)) {
            return fallback(className, mod);
        }
        EntitySupportProfile profile = BY_CLASS.get(simpleName(className));
        return profile == null ? fallback(className, mod) : profile;
    }

    /** Whether one class is supported with at least one trait; a fallback is never supported. */
    public static boolean supported(String className, String modId) {
        return profileFor(className, modId).supported();
    }

    /** The per-class program name; an unknown class keeps the base program as its fallback. */
    public static String programNameFor(String className, String modId) {
        return profileFor(className, modId).program();
    }

    /** The supported class names, special subset included. */
    public static List<String> supportedClasses() {
        return DECLARED.stream().filter(EntitySupportProfile::supported)
            .map(EntitySupportProfile::className).toList();
    }

    /** What the trait composite would decide for one class; installing binds it. */
    public record TraitDecision(boolean composite, long traits, String modelId, String reason) {
    }

    /** The decision the registry would take for one class: composite only for a supported class. */
    public static TraitDecision traitDecisionFor(Class<?> type) {
        EntitySupportProfile profile = profileFor(type.getName(), modIdOf(type));
        if (profile.supported()) {
            long traits = 0L;
            for (EntityTrait trait : profile.traits()) {
                traits |= 1L << trait.ordinal();
            }
            return new TraitDecision(true, traits, "trait:" + profile.className(),
                "declared-support");
        }
        return new TraitDecision(false, 0L, null, "unsupported-class");
    }

    private static EntitySupportProfile fallback(String className, String modId) {
        return new EntitySupportProfile(className, modId,
            EntitySupportProfile.SupportKind.C1_FALLBACK, Set.of(), "base");
    }

    private static EntitySupportProfile subset(String className, Set<EntityTrait> traits) {
        return profile(className, traits, EntitySupportProfile.SupportKind.SPECIAL_SUBSET);
    }

    private static EntitySupportProfile profile(String className, Set<EntityTrait> traits,
                                                EntitySupportProfile.SupportKind kind) {
        return new EntitySupportProfile(className, MINECRAFT, kind, traits,
            programKindOf(className, traits));
    }

    private static String programKindOf(String className, Set<EntityTrait> traits) {
        if (traits.contains(EntityTrait.BRAIN)) {
            return "brain";
        }
        if (traits.contains(EntityTrait.GOAL)) {
            return "goal";
        }
        if (traits.contains(EntityTrait.SENSOR)) {
            return "sensor";
        }
        if (traits.contains(EntityTrait.MOVE_CONTROL)) {
            return "move";
        }
        if (traits.contains(EntityTrait.NAVIGATION)) {
            return "navigation";
        }
        if (traits.contains(EntityTrait.MOB)) {
            return "mob";
        }
        if (traits.contains(EntityTrait.LIVING)) {
            return "living";
        }
        return "base";
    }

    private static Map<String, EntitySupportProfile> index(List<EntitySupportProfile> profiles) {
        Map<String, EntitySupportProfile> byClass = new LinkedHashMap<>();
        for (EntitySupportProfile profile : profiles) {
            byClass.put(profile.className(), profile);
        }
        return Map.copyOf(byClass);
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String modIdOf(Class<?> type) {
        Package declared = type.getPackage();
        String name = declared == null ? "" : declared.getName();
        return name.startsWith("net.minecraft.") ? MINECRAFT : name;
    }
}
