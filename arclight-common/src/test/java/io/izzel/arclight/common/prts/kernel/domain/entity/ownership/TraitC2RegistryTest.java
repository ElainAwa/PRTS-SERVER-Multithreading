/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T_LIVING + T_AI_OFF trait composite over the existing special models; AI stays C1. */
class TraitC2RegistryTest {

    static final class FakeLiving {
    }

    private static EntityTraitClassifier.Facts livingAiOff() {
        return new EntityTraitClassifier.Facts(true, true, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false);
    }

    private static EntityTraitClassifier.Facts livingAiOn() {
        return new EntityTraitClassifier.Facts(true, true, true, false, false, false, false,
            false, false, false, false, false, false, false, false, false);
    }

    private static EntityModelRegistry.TraitModelProvider provider() {
        return (type, profile) -> {
            long required = EntityTraitClassifier.T_LIVING | EntityTraitClassifier.T_AI_OFF;
            if ((profile.traits() & required) == required) {
                return TickModels.modelForClass(ArmorStand.class);
            }
            return null;
        };
    }

    @Test
    void traitCompositeReturnsExistingModelWithoutExactClass() {
        WholeTickModel oldExact = TickModels.modelForClass(FakeLiving.class);
        assertNull(oldExact, "old exact-only lookup should not admit a foreign class");
        WholeTickModel self = EntityModelRegistry.modelOf(FakeLiving.class, livingAiOff(), "examplemod", provider());
        assertNotNull(self, "trait composite should admit T_LIVING+T_AI_OFF");
        assertEquals(TickModels.armorStand(), self);
    }

    @Test
    void specialTypeModelStillWinsOverTraitComposite() {
        EntityModelRegistry.Decision special = new EntityModelRegistry.Decision(
            EntityModelRegistry.Kind.SPECIAL_TYPE, "special_exact", 0L, "special");
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(FakeLiving.class,
            livingAiOff(), "minecraft", (type, profile) -> special, (type, profile) -> {
                throw new AssertionError("trait provider must not be consulted after special");
            });
        assertEquals(EntityModelRegistry.Kind.SPECIAL_TYPE, decision.kind());
    }

    @Test
    void aiOnStaysC1() {
        WholeTickModel model = EntityModelRegistry.modelOf(FakeLiving.class, livingAiOn(), "minecraft", provider());
        assertNull(model, "AI on must not be admitted by the T_AI_OFF provider");
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(FakeLiving.class,
            livingAiOn(), "minecraft", null, (type, profile) -> null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind());
    }

    @Test
    void providerIsTestOnlyAndAbsentByDefault() {
        EntityModelRegistry.clearTraitModelProviderForTests();
        EntityModelRegistry.Decision decision = EntityModelRegistry.decide(FakeLiving.class,
            livingAiOff(), "minecraft", null, null);
        assertEquals(EntityModelRegistry.Kind.C1_FALLBACK, decision.kind());
    }
}
