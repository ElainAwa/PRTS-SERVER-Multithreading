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

    private EntityModelRegistry() {
    }

    /** Production decision; existing five special models keep priority, everything else is C1. */
    public static Decision decide(Entity entity) {
        WholeTickModel model = TickModels.of(entity);
        if (model != null) {
            EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(entity);
            return new Decision(Kind.SPECIAL_TYPE, model.name(), profile.traits(), "special_model");
        }
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(entity);
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
}
