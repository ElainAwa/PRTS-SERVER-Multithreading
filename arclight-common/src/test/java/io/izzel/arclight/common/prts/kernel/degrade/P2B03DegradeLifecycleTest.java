/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The five-rung lifecycle drill: every rung enters in order, acts only when the action flag is
 * on, returns through its own window plus second condition, and publishes no action while the
 * default-off flag is off. */
class P2B03DegradeLifecycleTest {

    @Test
    void everyRungCarriesTriggerActionSignalAndReturnAndItsOwnCode() {
        DegradeLadder ladder = new DegradeLadder(() -> false);

        assertEquals(5, ladder.rungs().size());
        for (DegradeLevel level : new DegradeLevel[] {DegradeLevel.B1, DegradeLevel.B2,
            DegradeLevel.B3, DegradeLevel.B4, DegradeLevel.B5}) {
            DegradeLadder.Rung rung = ladder.rung(level);
            assertNotNull(rung);
            assertEquals(level, rung.level());
            assertNotNull(rung.code());
            assertFalse(rung.trigger().isBlank());
            assertFalse(rung.action().isBlank());
            assertFalse(rung.signal().isBlank());
            assertFalse(rung.returnCondition().isBlank());
        }
    }

    @Test
    void eachRungEntersActsAndReturnsWhenTheActionFlagIsOn() {
        for (DegradeLevel target : new DegradeLevel[] {DegradeLevel.B1, DegradeLevel.B2,
            DegradeLevel.B3, DegradeLevel.B4, DegradeLevel.B5}) {
            DegradeLadder ladder = new DegradeLadder(() -> true);
            for (DegradeLevel level : new DegradeLevel[] {DegradeLevel.B1, DegradeLevel.B2,
                DegradeLevel.B3, DegradeLevel.B4, DegradeLevel.B5}) {
                if (level.ordinal() > target.ordinal()) {
                    break;
                }
                DegradeLadder.Advance step = ladder.noteEntered(level, 10L);
                assertFalse(step.skipped());
                assertEquals(level, step.reached());
            }
            assertTrue(ladder.noteEffective(target));
            assertEquals(1L, ladder.counters(target).effective());
            for (DegradeLevel level : DegradeLevel.values()) {
                if (level == DegradeLevel.NONE || level.ordinal() > target.ordinal()) {
                    continue;
                }
                assertTrue(ladder.noteReturned(level));
            }
            assertEquals(DegradeLevel.NONE, ladder.sign().deepest());
            assertEquals(1L, ladder.counters(target).returned());
        }
    }

    @Test
    void theActionFlagOffPublishesNoEffectiveOrReturnForAnyRung() {
        DegradeLadder ladder = new DegradeLadder(() -> false);
        for (DegradeLevel level : DegradeLevel.values()) {
            if (level == DegradeLevel.NONE) {
                continue;
            }
            ladder.noteEntered(level, 10L);
            assertFalse(ladder.noteEffective(level));
            assertFalse(ladder.noteReturned(level));
        }
        assertEquals(0L, ladder.effectiveTotal());
        assertEquals(0L, ladder.returnedTotal());
        assertFalse(ladder.sign().actionsEnabled());
    }

    @Test
    void everyRungHasAReadableReturnGateAndAResetAfterAnOverrun() {
        for (DegradeLevel level : new DegradeLevel[] {DegradeLevel.B1, DegradeLevel.B2,
            DegradeLevel.B3, DegradeLevel.B4, DegradeLevel.B5}) {
            DegradeLadder ladder = new DegradeLadder(() -> true);
            ladder.noteEntered(level, 10L);
            ladder.noteTick(false);
            ladder.noteTick(false);
            ladder.noteTick(false);
            assertEquals("second_condition_unbound", ladder.gate(level, 3).blockedBy());
            ladder.bindSecondCondition(level, "fixture.second", () -> false);
            assertEquals("second_condition", ladder.gate(level, 3).blockedBy());
            ladder.bindSecondCondition(level, "fixture.second", () -> true);
            assertTrue(ladder.gate(level, 3).ready());
            ladder.noteTick(true);
            assertFalse(ladder.gate(level, 3).ready());
            assertEquals("clean_ticks", ladder.gate(level, 3).blockedBy());
        }
    }

    @Test
    void theDeclaredWindowAndTheLadderDefaultsAreReadable() {
        DegradeLadder ladder = new DegradeLadder(() -> false);
        assertEquals(3, DegradeLadder.DECLARED_RETURN_TICKS);
        assertEquals(DegradeLevel.NONE, ladder.sign().deepest());
        assertEquals(0L, ladder.enteredTotal());
        assertEquals(0L, ladder.effectiveTotal());
        assertEquals(0L, ladder.returnedTotal());
        assertEquals(0, FaultInjection.ladderWalkStep(), "no injection is the default");
        assertEquals(0, FaultInjection.ladderReturnStep(), "no injection is the default");
    }

    @Test
    void resetClearsTheLadderAndEveryCounter() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        ladder.noteEntered(DegradeLevel.B4, 10L);
        ladder.noteEffective(DegradeLevel.B4);
        ladder.noteTick(false);
        ladder.bindSecondCondition(DegradeLevel.B4, "fixture", () -> true);

        ladder.reset();

        assertEquals(DegradeLevel.NONE, ladder.sign().deepest());
        assertEquals(0L, ladder.enteredTotal());
        assertEquals(0L, ladder.effectiveTotal());
        assertEquals(0L, ladder.returnedTotal());
        assertEquals(0L, ladder.sign().ticksObserved());
        assertEquals(0L, ladder.sign().cleanTicks());
    }
}
