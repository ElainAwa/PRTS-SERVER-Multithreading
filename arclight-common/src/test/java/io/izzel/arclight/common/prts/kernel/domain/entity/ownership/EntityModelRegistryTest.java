/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Registry precedence: special type > trait composite (none yet) > C1 fallback; no widening. */
class EntityModelRegistryTest {

    static class SpecialFake {
    }

    static class NormalFake {
    }

    private static EntityTraitClassifier.Facts facts() {
        return new EntityTraitClassifier.Facts(false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false);
    }

    @Test
    void specialLookupWinsOverTraitAndC1() {
        EntityModelRegistry.SpecialLookup special = (type, profile) -> type == SpecialFake.class
            ? new EntityModelRegistry.Decision(EntityModelRegistry.Kind.SPECIAL_TYPE,
                "special_fake", profile.traits(), "special_model") : null;
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(SpecialFake.class,
            facts(), "minecraft", special);
        assertEquals(EntityModelRegistry.Kind.SPECIAL_TYPE, decision.kind());
        assertEquals("special_fake", decision.modelId());
    }

    @Test
    void noProviderIsC1FallbackWithReason() {
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(NormalFake.class,
            facts(), "examplemod", null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind());
        assertNull(decision.modelId());
        assertEquals("no_trait_model", decision.reason());
    }

    @Test
    void moddedClassStaysC1WhenNoProvider() {
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(NormalFake.class,
            facts(), "examplemod", (type, profile) -> null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind());
        assertEquals("no_trait_model", decision.reason());
    }
}
