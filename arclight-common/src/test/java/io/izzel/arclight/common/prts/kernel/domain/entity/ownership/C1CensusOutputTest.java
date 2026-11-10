/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C1 census snapshot/evidence output and unaccounted proof, no formal reading key. */
class C1CensusOutputTest {

    static final class FakeA {
    }

    static final class FakeB {
    }

    @BeforeEach
    void reset() {
        C1Census.reset();
    }

    @Test
    void snapshotAccountsEveryObservedRow() {
        long livingAiOff = EntityTraitClassifier.T_LIVING | EntityTraitClassifier.T_AI_OFF;
        C1Census.record(FakeA.class, "minecraft", livingAiOff, C1Census.Outcome.OBSERVED, 0L);
        C1Census.record(FakeA.class, "minecraft", livingAiOff, C1Census.Outcome.CLAIMED, 0L);
        C1Census.record(FakeB.class, "minecraft", livingAiOff, C1Census.Outcome.OBSERVED, 0L);
        C1Census.record(FakeB.class, "minecraft", livingAiOff, C1Census.Outcome.REFUSED, 0L);
        C1Census.Snapshot snapshot = C1Census.snapshot();
        assertEquals(2L, snapshot.observed());
        assertEquals(1L, snapshot.claimed());
        assertEquals(1L, snapshot.refused());
        assertEquals(0L, snapshot.unaccounted());
        assertEquals(2, snapshot.rows().size());
        assertTrue(C1Census.evidenceLine().startsWith("[PRTS] entity-c1:"));
    }

    @Test
    void unaccountedIsVisibleWhenADecisionIsMissing() {
        long livingAiOff = EntityTraitClassifier.T_LIVING | EntityTraitClassifier.T_AI_OFF;
        C1Census.record(FakeA.class, "examplemod", livingAiOff, C1Census.Outcome.OBSERVED, 0L);
        assertEquals(1L, C1Census.snapshot().unaccounted());
    }
}
