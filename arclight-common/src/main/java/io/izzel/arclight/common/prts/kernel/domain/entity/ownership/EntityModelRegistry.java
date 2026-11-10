/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Decision layer over the existing special models and the (not yet supplied) trait models.
 * Priority is special type model, then a trait composite, then C1 host fallback. A missing
 * provider is a normal C1 result; this class never widens eligibility or changes a tick.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import net.minecraft.world.entity.Entity;

/** Chooses the model class for a row and names the fallback when there is none. */
public final class EntityModelRegistry {

    public enum Kind {
        SPECIAL_TYPE, TRAIT_COMPOSITE, C1_FALLBACK
    }

    /** One decision: the winning kind, model id when there is one, trait/profile and reason. */
    public record Decision(Kind kind, String modelId, long traits, String reason) {
    }

    /** Test seam for the special-model table; production uses the existing TickModels registry. */
    @FunctionalInterface
    public interface SpecialLookup {
        Decision special(Class<?> type, EntityTraitClassifier.Profile profile);
    }

    /** Trait-composite decision provider; no provider is registered in production. */
    @FunctionalInterface
    public interface TraitProvider {
        Decision trait(Class<?> type, EntityTraitClassifier.Profile profile);
    }

    /** Trait-composite model provider; the ownership path asks this for a model to claim. */
    @FunctionalInterface
    public interface TraitModelProvider {
        WholeTickModel model(Class<?> type, EntityTraitClassifier.Profile profile);
    }

    private static final boolean TRAIT_C2_TEST = Boolean.getBoolean("arclight.prts.traitC2Test");
    private static volatile TraitProvider traitProvider;
    private static volatile TraitModelProvider traitModelProvider;

    static {
        if (TRAIT_C2_TEST) {
            System.out.println("[PRTS] traitC2Test provider installed");
            traitModelProvider = (type, profile) -> {
                long required = EntityTraitClassifier.T_LIVING | EntityTraitClassifier.T_AI_OFF;
                return (profile.traits() & required) == required ? TickModels.modelForClass(type) : null;
            };
        }
    }

    private EntityModelRegistry() {
    }

    /** Test-only installation; production never installs a provider in this batch. */
    public static void installTraitProviderForTests(TraitProvider provider) {
        traitProvider = provider;
    }

    public static void clearTraitProviderForTests() {
        traitProvider = null;
        traitModelProvider = null;
    }

    /** Test-only model provider installation; production never installs one. */
    public static void installTraitModelProviderForTests(TraitModelProvider provider) {
        traitModelProvider = provider;
    }

    public static void clearTraitModelProviderForTests() {
        traitModelProvider = null;
    }

    /** Class-level model resolution for tests; uses the same trait provider shape. */
    public static WholeTickModel modelOf(Class<?> type, EntityTraitClassifier.Facts facts, String modId,
                                         TraitModelProvider provider) {
        if (provider == null) {
            return null;
        }
        return provider.model(type, EntityTraitClassifier.classify(type, facts, modId));
    }

    /** The model the ownership path may claim for this row; null leaves it to C1. */
    public static WholeTickModel modelOf(Entity entity) {
        WholeTickModel special = TickModels.of(entity);
        if (special != null) {
            return special;
        }
        TraitModelProvider provider = traitModelProvider;
        if (provider != null) {
            try {
                return provider.model(entity.getClass(), EntityTraitClassifier.classify(entity));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Production decision; existing five special models keep priority, everything else is C1. */
    public static Decision decide(Entity entity) {
        WholeTickModel model = TickModels.of(entity);
        if (model != null) {
            EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(entity);
            return new Decision(Kind.SPECIAL_TYPE, model.name(), profile.traits(), "special_model");
        }
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(entity);
        TraitProvider provider = traitProvider;
        if (provider != null) {
            Decision chosen = provider.trait(entity.getClass(), profile);
            if (chosen != null) {
                return chosen;
            }
        }
        return new Decision(Kind.C1_FALLBACK, null, profile.traits(), "no_trait_model");
    }

    /** Class-level decision for tests and the future provider SPI. */
    public static Decision decide(Class<?> type, EntityTraitClassifier.Facts facts, String modId,
                                  SpecialLookup special) {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(type, facts, modId);
        Decision chosen = special == null ? null : special.special(type, profile);
        if (chosen != null) {
            return chosen;
        }
        return new Decision(Kind.C1_FALLBACK, null, profile.traits(), "no_trait_model");
    }

    /** Class-level decision with both test seams; no production provider is installed here. */
    public static Decision decide(Class<?> type, EntityTraitClassifier.Facts facts, String modId,
                                  SpecialLookup special, TraitProvider trait) {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(type, facts, modId);
        Decision chosen = special == null ? null : special.special(type, profile);
        if (chosen != null) {
            return chosen;
        }
        if (trait != null) {
            chosen = trait.trait(type, profile);
            if (chosen != null) {
                return chosen;
            }
        }
        return new Decision(Kind.C1_FALLBACK, null, profile.traits(), "no_trait_model");
    }
}
