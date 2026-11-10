/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;

/** Test-only trait composite registration; production has no provider. */
class TraitCompositeRegistryTest {

    static class AiFake {
    }

    private static EntityTraitClassifier.Facts aiFacts() {
        return new EntityTraitClassifier.Facts(true, true, true, true, false, false, false,
            false, false, false, false, false, false, false, false, false);
    }

    @AfterEach
    void clear() {
        EntityModelRegistry.clearTraitProviderForTests();
        EntityModelRegistry.clearTraitModelProviderForTests();
    }

    @Test
    void installedTraitProviderWinsOverC1() {
        EntityModelRegistry.installTraitProviderForTests((type, profile) ->
            (profile.traits() & EntityTraitClassifier.T_AI_ON) != 0L
                ? new EntityModelRegistry.Decision(EntityModelRegistry.Kind.TRAIT_COMPOSITE,
                    "mob_ai_test", profile.traits(), "test_only") : null);
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(AiFake.class, aiFacts(),
            "minecraft", null, null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind(),
            "class-level overload does not read the static provider");
        EntityModelRegistry.Decision withProvider = EntityModelRegistry.decide(AiFake.class,
            aiFacts(), "minecraft", null, (type, profile) ->
                (profile.traits() & EntityTraitClassifier.T_AI_ON) != 0L
                    ? new EntityModelRegistry.Decision(EntityModelRegistry.Kind.TRAIT_COMPOSITE,
                        "mob_ai_test", profile.traits(), "test_only") : null);
        assertEquals(EntityModelRegistry.Kind.TRAIT_COMPOSITE, withProvider.kind());
        assertEquals("mob_ai_test", withProvider.modelId());
    }

    @Test
    void traitModelProviderReturnsTheAiModelForAiFacts() {
        MobAiTickModel model = new MobAiTickModel();
        WholeTickModel resolved = EntityModelRegistry.modelOf(AiFake.class, aiFacts(), "minecraft",
            (type, profile) -> (profile.traits() & EntityTraitClassifier.T_AI_ON) != 0L ? model : null);
        assertEquals(model, resolved);
    }

    @Test
    void noProviderKeepsC1() {
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(AiFake.class, aiFacts(),
            "minecraft", null, null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind());
    }
}
