/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arena slots: ownership, generations and the pin pairing that proves nothing leaked. */
class ArenaSlotTest {

    @Test
    void aSlotBelongsToOneBatchAndRefusesEveryOtherWriter() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(7L, "world", "r0.0", 7, 4);
        assertNotNull(slot);
        assertEquals(7L, slot.ownerBatchId());
        assertEquals(ArenaSlot.State.PINNED, slot.state());

        long generation = slot.ref().slotGeneration();
        assertFalse(slot.publish(8L, generation), "another batch must not publish this slot");
        assertFalse(slot.publish(7L, generation + 1L), "a stale generation must not publish");
        assertTrue(slot.publish(7L, generation));
        assertEquals(ArenaSlot.State.PUBLISHED, slot.state());
    }

    @Test
    void releasingASlotBumpsItsGenerationFirst() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot first = ledger.claim(1L, "world", "r0.0", 7, 4);
        long generation = first.ref().slotGeneration();
        assertTrue(ledger.release(first));
        assertEquals(generation + 1L, first.generation());
        assertEquals(ArenaSlot.State.FREE, first.state());
        assertFalse(first.publish(1L, generation), "the old generation is no longer owned");

        ArenaSlot second = ledger.claim(2L, "world", "r0.0", 7, 8);
        assertEquals(first, second, "a free slot is reused");
        assertEquals(2L, second.ownerBatchId());
        assertEquals(1L, ledger.generationBumps());
    }

    @Test
    void aCrossWorldClaimIsRefusedInsteadOfReusingTheSegment() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSegment first = ledger.segment("world-a", "r0.0", 7);
        ArenaSegment second = ledger.segment("world-b", "r0.0", 7);
        assertNotEquals(first.ref().key(), second.ref().key());
        ArenaSlot slot = ledger.claim(1L, "world-a", "r0.0", 7, 2);
        assertNotNull(slot);
        assertFalse(second.release(slot), "a slot must not be released into another world");
        assertTrue(first.release(slot));
    }

    @Test
    void thePinPairsCloseOnlyWhenEverySlotWasReleased() {
        ArenaLedger ledger = new ArenaLedger();
        List<ArenaSlot> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slots.add(ledger.claim(i + 1L, "world", "r" + i + ".0", 7, 4));
        }
        assertFalse(ledger.pinPairsHold());
        for (ArenaSlot slot : slots) {
            ledger.release(slot);
        }
        assertTrue(ledger.pinPairsHold());
        assertEquals(3L, ledger.claims());
        assertEquals(3L, ledger.releases());
        assertEquals(0, ledger.pinnedCount());
    }

    @Test
    void aForeignWriteIsCountedInsteadOfAccepted() {
        ArenaLedger ledger = new ArenaLedger();
        assertEquals(1L, ledger.noteForeignWrite());
        assertEquals(2L, ledger.noteForeignWrite());
        assertEquals(2L, ledger.foreignWrites());
    }

    @Test
    void aScratchNeverReadsTheTailOfALongerBatch() {
        ArenaScratch scratch = new ArenaScratch();
        scratch.reset(4);
        for (int i = 0; i < 4; i++) {
            scratch.write(i, i, i, i, i, i, i, i, i, i);
        }
        assertEquals(4, scratch.filled());
        scratch.reset(2);
        assertEquals(0, scratch.filled());
        scratch.write(0, 9.0, 9.0, 9.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        assertEquals(1, scratch.filled());
        assertEquals(9.0, scratch.posX(0));
    }

    @Test
    void releasingEverythingDropsTheDirectoryAndTheCounters() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(1L, "world", "r0.0", 7, 2);
        assertNotNull(slot);
        slot.release();
        ledger.reset();
        assertEquals(0L, ledger.claims());
        assertEquals(0L, ledger.releases());
        assertEquals(0, ledger.pinnedCount());
        assertTrue(ledger.pinPairsHold());
    }
}
