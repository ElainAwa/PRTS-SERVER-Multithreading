/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The zero-effect criterion: a rung that claims an effect next to a metered value that did not
 * move is a finding; a rung that moved it is not, and a rung that claims nothing is not either. */
class ZeroEffectDetectorTest {

    @Test
    void anEffectWithAnUnchangedMetricIsDetected() {
        ZeroEffectDetector detector = new ZeroEffectDetector();
        detector.watch("b1", 10L, 5.0);

        ZeroEffectDetector.Verdict verdict = detector.evaluate("b1", 15L, 5.0, 1L, 1L, 5);

        assertEquals(ZeroEffectDetector.State.ZERO_EFFECT, verdict.state());
        assertTrue(verdict.violation());
        assertEquals(0.0, verdict.delta());
        assertEquals(1L, detector.zeroEffectTotal());
    }

    @Test
    void anEffectThatMovedTheMetricIsNotAFinding() {
        ZeroEffectDetector detector = new ZeroEffectDetector();
        detector.watch("b2", 10L, 5.0);

        ZeroEffectDetector.Verdict verdict = detector.evaluate("b2", 15L, 9.5, 1L, 1L, 5);

        assertEquals(ZeroEffectDetector.State.CHANGED, verdict.state());
        assertFalse(verdict.violation());
        assertEquals(4.5, verdict.delta());
        assertEquals(0L, detector.zeroEffectTotal());
        assertEquals(1L, detector.changedTotal());
    }

    @Test
    void anEffectNobodyClaimedIsUnprovenAndNotAFinding() {
        ZeroEffectDetector detector = new ZeroEffectDetector();
        detector.watch("b3", 10L, 5.0);

        ZeroEffectDetector.Verdict verdict = detector.evaluate("b3", 15L, 5.0, 1L, 0L, 5);

        assertEquals(ZeroEffectDetector.State.UNPROVEN, verdict.state());
        assertEquals(0L, detector.zeroEffectTotal());
        assertEquals(1L, detector.unprovenTotal());
    }

    @Test
    void aWindowThatHasNotElapsedJudgesNothingAndOneFindingIsNotRepeated() {
        ZeroEffectDetector detector = new ZeroEffectDetector();
        detector.watch("b4", 10L, 5.0);

        assertEquals(ZeroEffectDetector.State.PENDING,
            detector.evaluate("b4", 12L, 5.0, 1L, 1L, 5).state());
        assertEquals(ZeroEffectDetector.State.ZERO_EFFECT,
            detector.evaluate("b4", 15L, 5.0, 1L, 1L, 5).state());
        detector.watch("b4", 20L, 5.0);
        assertEquals(ZeroEffectDetector.State.NONE,
            detector.evaluate("b4", 25L, 5.0, 1L, 1L, 5).state());
        assertEquals(1L, detector.zeroEffectTotal());
    }
}
