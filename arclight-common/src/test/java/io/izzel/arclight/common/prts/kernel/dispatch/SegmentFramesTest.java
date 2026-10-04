/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame of one segment and the ledger that sums it: the two row sets apart, every ownership row
 * in exactly one outcome, and the counters reading zero while nothing was booked. */
class SegmentFramesTest {

    private static final long TICK = 42L;

    @Test
    void theTwoRowSetsOfAFrameAreBookedApart() {
        SegmentFrames.Frame frame = frame(3, 2, 3, 0, 0, 0);
        assertEquals(5, frame.rows(), "the rows of the two sets were not summed");
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        ledger.note(frame);
        assertEquals(1, ledger.frames());
        assertEquals(3, ledger.ownedRows());
        assertEquals(2, ledger.observedRows());
        assertEquals(5, ledger.rows());
        assertEquals(TICK, ledger.lastTick());
        assertTrue(ledger.accounted(), "three ownership rows with three commits were not accounted");
        assertTrue(ledger.setsApart());
        assertEquals(0, ledger.setConflicts());
        assertEquals(0, ledger.brokenFrames());
        assertEquals(0, ledger.brokenLedgers());
    }

    @Test
    void anOwnershipRowWithoutAnOutcomeBreaksTheAccount() {
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        ledger.note(frame(2, 0, 1, 0, 0, 0));
        assertFalse(ledger.accounted(),
            "an ownership row that ended in no outcome was accepted as accounted");
        assertEquals(1, ledger.brokenLedgers());
    }

    @Test
    void aRowInBothSetsIsCountedAsABrokenFrame() {
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        ledger.note(frame(1, 1, 1, 0, 0, 1));
        assertFalse(ledger.setsApart(), "a row booked in both sets stayed apart");
        assertEquals(1, ledger.setConflicts());
        assertEquals(1, ledger.brokenFrames());
        assertTrue(ledger.accounted());
    }

    @Test
    void everyOutcomeAndCheckOfTheFramesIsSummed() {
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        ledger.note(frame(2, 1, 1, 1, 0, 0));
        ledger.note(frame(3, 3, 0, 0, 3, 0));
        assertEquals(2, ledger.frames());
        assertEquals(5, ledger.ownedRows());
        assertEquals(4, ledger.observedRows());
        assertEquals(1, ledger.committed());
        assertEquals(1, ledger.fellBack());
        assertEquals(3, ledger.notEntered());
        assertTrue(ledger.accounted(), "five ownership rows with five outcomes were not accounted");
    }

    @Test
    void theLedgerReadsZeroBeforeAnyFrame() {
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        assertEquals(0, ledger.frames());
        assertEquals(0, ledger.rows());
        assertEquals(-1, ledger.lastTick(), "a ledger that booked nothing named a tick");
        assertTrue(ledger.accounted());
        assertTrue(ledger.setsApart());
        assertTrue(ledger.evidenceLine().contains("frames=0 rows=0 owned_rows=0 observed_rows=0"),
            "the zero line does not report both row sets at zero");
    }

    @Test
    void theLedgerForgetsEveryCounterOnReset() {
        SegmentFrames.Ledger ledger = new SegmentFrames.Ledger();
        ledger.note(frame(4, 4, 4, 0, 0, 1));
        ledger.reset();
        assertEquals(0, ledger.frames());
        assertEquals(0, ledger.ownedRows());
        assertEquals(0, ledger.observedRows());
        assertEquals(0, ledger.setConflicts());
        assertEquals(-1, ledger.lastTick());
    }

    @Test
    void theMigratedFrameFaceIsOffByDefault() {
        assertFalse(Boolean.getBoolean("arclight.prts.frameMigration"),
            "a test run must not carry the migration property");
    }

    /** A frame whose row account says what its own numbers say: owned == committed + fellBack +
     * notEntered, so a frame built with a row left over is booked as a broken ledger. */
    private static SegmentFrames.Frame frame(int owned, int observed, int committed, int fellBack,
                                             int notEntered, int setConflicts) {
        return new SegmentFrames.Frame(TICK, "minecraft:overworld", 1L, owned, observed, committed,
            fellBack, notEntered, observed, setConflicts, 0, 0, 0, 0, 0,
            owned == committed + fellBack + notEntered);
    }
}
