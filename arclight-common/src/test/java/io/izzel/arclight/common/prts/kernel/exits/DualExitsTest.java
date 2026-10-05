/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.exits;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two exits: one counter base, one reading name space, two publications that share the tick
 * they were taken on, and the guards that keep a window statistic off the control side. */
class DualExitsTest {

    private static DualExits.Sample sample(long tick, double margin, long overruns, double left) {
        return new DualExits.Sample(tick, margin, overruns, 0L, 0.5, left, "normal");
    }

    @Test
    void bothExitsReadTheSameValuesOfTheSameTick() {
        DualExits exits = new DualExits(3);
        for (int index = 1; index <= 3; index++) {
            exits.noteTick(sample(index, index, index, 4.0), List.of());
        }

        DualExits.SameSource same = exits.sameSource();

        assertEquals(3L, exits.control().tickIndex());
        assertEquals(3L, exits.judgement().tickIndex());
        assertEquals(3L, exits.judgement().windowTicks());
        assertEquals(DualExits.controlNames().size(), same.fields());
        assertEquals(same.fields(), same.sameOrigin());
        assertEquals(same.fields(), same.sameValue());
        assertTrue(same.equal());
        assertEquals(exits.control().reading("overrun_hits").text(),
            exits.judgement().tailReading("overrun_hits").text());
        assertEquals("shares.last_table.overrun_hits",
            exits.control().reading("overrun_hits").origin());
        assertEquals(same.controlTick(), same.judgementTick());
    }

    @Test
    void theComparisonStaysOnTheTickTheWindowClosedOn() {
        DualExits exits = new DualExits(2);
        for (int index = 1; index <= 4; index++) {
            exits.noteTick(sample(index, index, index, 4.0), List.of());
        }

        DualExits.SameSource same = exits.sameSource();

        assertEquals(4L, exits.control().tickIndex());
        assertEquals(4L, exits.judgement().tickIndex());
        assertEquals(4L, same.controlTick());
        assertTrue(same.equal());
    }

    @Test
    void theComparisonCanFail() {
        DualExits left = new DualExits(2);
        DualExits right = new DualExits(2);
        for (int index = 1; index <= 2; index++) {
            left.noteTick(sample(index, 1.0, 1L, 4.0), List.of());
            right.noteTick(new DualExits.Sample(index, 7.0, 9L, 5L, 0.9, 1.0, "degraded:b1"),
                List.of());
        }

        DualExits.SameSource same = DualExits.compare(left.control(), right.judgement());

        assertFalse(same.equal());
        assertEquals(0, same.sameValue());
        assertEquals(same.fields(), same.sameOrigin());
    }

    @Test
    void aWindowStatisticIsRefusedOnTheControlSideAndAWritePathOnTheJudgementSide() {
        DualExits exits = new DualExits(2);
        exits.noteTick(sample(1L, 1.0, 0L, 4.0), List.of());

        assertFalse(exits.offerWindowStatistic("overrun_hits.window_sum", 12.0));
        assertFalse(exits.noteWriteDependency(DualExits.Plane.JUDGEMENT));
        assertEquals(1L, exits.controlWindowFeeds());
        assertEquals(1L, exits.judgementWriteDependencies());
    }

    @Test
    void aMissingCounterIsNamedInsteadOfZeroed() {
        DualExits exits = new DualExits(1);
        DualExits.Frame frame = exits.noteTick(sample(1L, 0.0, 0L, 0.0),
            List.of("reserve_remaining_ms"));

        assertFalse(frame.complete());
        assertEquals(6, frame.readings().size());
        assertNotNull(frame.reading("reserve_remaining_ms"));
        assertEquals("missing", frame.reading("reserve_remaining_ms").text());
        assertEquals("0.000", frame.reading("overrun_hits").text());
        assertFalse(frame.groupComplete(DualExits.Group.A));
        assertTrue(frame.groupComplete(DualExits.Group.B));
        assertTrue(frame.groupComplete(DualExits.Group.SHARED));
    }
}
