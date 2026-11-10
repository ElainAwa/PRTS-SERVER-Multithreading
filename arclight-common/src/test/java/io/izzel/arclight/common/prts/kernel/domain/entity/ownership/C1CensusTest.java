/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The C1 census is observation-only and keeps one key per class/modid/trait. */
class C1CensusTest {

    static class FakeA {
    }

    static class FakeB {
    }

    @BeforeEach
    void reset() {
        C1Census.reset();
    }

    @Test
    void recordsObservedClaimedRefusedFallbackAndHostTime() {
        C1Census.record(FakeA.class, "minecraft", 1L, C1Census.Outcome.OBSERVED, 0L);
        C1Census.record(FakeA.class, "minecraft", 1L, C1Census.Outcome.CLAIMED, 0L);
        C1Census.record(FakeA.class, "minecraft", 1L, C1Census.Outcome.REFUSED, 0L);
        C1Census.record(FakeA.class, "minecraft", 1L, C1Census.Outcome.FALLBACK, 5L);
        String line = C1Census.evidenceLine();
        assertTrue(line.contains(FakeA.class.getName()), line);
        assertTrue(line.contains("=1,1,1,1,0,5"), line);
    }

    @Test
    void modidAndTraitArePartOfTheKey() {
        C1Census.record(FakeA.class, "minecraft", 1L, C1Census.Outcome.OBSERVED, 0L);
        C1Census.record(FakeA.class, "examplemod", 1L, C1Census.Outcome.OBSERVED, 0L);
        String line = C1Census.evidenceLine();
        assertTrue(line.contains("minecraft"), line);
        assertTrue(line.contains("examplemod"), line);
    }

    @Test
    void playerC0IsCountedOutsideTheClassKeys() {
        C1Census.record(FakeB.class, "minecraft", EntityTraitClassifier.T_C0_PLAYER,
            C1Census.Outcome.OBSERVED, 0L);
        String line = C1Census.evidenceLine();
        assertTrue(line.contains("c0=1"), line);
        assertTrue(!line.contains(FakeB.class.getName()), line);
    }
}
