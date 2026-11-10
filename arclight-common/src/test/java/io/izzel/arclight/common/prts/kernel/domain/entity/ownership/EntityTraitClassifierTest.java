/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Class-level trait classification: capabilities, modid, and method profile without a live entity. */
class EntityTraitClassifierTest {

    static class PlainEntity {
    }

    static class FakeOverrideEntity extends PlainEntity {
        @Override
        public String toString() {
            return "fake";
        }

        void tick() {
        }
    }

    static class FakeModdedMob extends FakeOverrideEntity {
        void serverAiStep() {
        }
    }

    private static EntityTraitClassifier.Facts facts(boolean mob, boolean aiOn, boolean player) {
        return new EntityTraitClassifier.Facts(mob, mob, aiOn, false, false, false, false,
            false, false, false, false, false, false, false, false, player);
    }

    @Test
    void baseEntityHasOnlyBaseTraitAndNoOverride() {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(PlainEntity.class,
            facts(false, false, false), "minecraft");
        assertEquals(EntityTraitClassifier.T_BASE, profile.traits());
        assertEquals(0, profile.methodMask());
        assertEquals("minecraft", profile.modId());
    }

    @Test
    void overrideTickIsVisibleInMethodProfileAndSpecialTrait() {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(FakeOverrideEntity.class,
            facts(false, false, false), "minecraft");
        assertNotEquals(0, profile.methodMask() & EntityTraitClassifier.M_ENTITY_TICK);
        assertTrue((profile.traits() & EntityTraitClassifier.T_SPECIAL_TICK) != 0L);
    }

    @Test
    void moddedSubclassKeepsTraitBitsAndGetsModdedBit() {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(FakeModdedMob.class,
            new EntityTraitClassifier.Facts(true, true, true, true, false, false, false,
                false, false, false, false, false, false, false, false, false), "examplemod");
        assertTrue((profile.traits() & EntityTraitClassifier.T_LIVING) != 0L);
        assertTrue((profile.traits() & EntityTraitClassifier.T_MOB) != 0L);
        assertTrue((profile.traits() & EntityTraitClassifier.T_AI_ON) != 0L);
        assertTrue((profile.traits() & EntityTraitClassifier.T_PATHFINDER) != 0L);
        assertTrue((profile.traits() & EntityTraitClassifier.T_MODDED) != 0L);
        assertNotEquals(0, profile.methodMask() & EntityTraitClassifier.M_SERVER_AI);
    }

    @Test
    void playerIsMarkedC0EvenWhenOtherFlagsAreSet() {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(PlainEntity.class,
            facts(true, true, true), "minecraft");
        assertTrue((profile.traits() & EntityTraitClassifier.T_C0_PLAYER) != 0L);
    }

    @Test
    void aiOffIsNotAiOn() {
        EntityTraitClassifier.Profile profile = EntityTraitClassifier.classify(PlainEntity.class,
            facts(true, false, false), "minecraft");
        assertTrue((profile.traits() & EntityTraitClassifier.T_AI_OFF) != 0L);
        assertEquals(0L, profile.traits() & EntityTraitClassifier.T_AI_ON);
    }
}
