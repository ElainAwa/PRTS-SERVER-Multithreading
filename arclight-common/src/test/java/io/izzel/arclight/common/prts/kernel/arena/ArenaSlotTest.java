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

/** The arena slots: ownership, generations, leases and the pin pairing that proves nothing leaked. */
class ArenaSlotTest {

    private static final long PLAN_EPOCH = 1L;

    @Test
    void aSlotBelongsToOneBatchAndRefusesEveryOtherWriter() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(7L, "world", "r0.0", 7, 4);
        assertNotNull(slot);
        assertEquals(7L, slot.ownerBatchId());
        assertEquals(ArenaSlot.State.PINNED, slot.state());

        ArenaSlot.Lease lease = slot.lease(PLAN_EPOCH);
        assertFalse(slot.publish(new ArenaSlot.Lease(slot.segment(), 0, lease.generation(), 8L,
            PLAN_EPOCH, lease.scratch())), "another batch must not publish this slot");
        assertFalse(slot.publish(new ArenaSlot.Lease(slot.segment(), 0, lease.generation() + 1L, 7L,
            PLAN_EPOCH, lease.scratch())), "a stale generation must not publish");
        assertTrue(slot.publish(lease));
        assertEquals(ArenaSlot.State.PUBLISHED, slot.state());
    }

    @Test
    void releasingASlotBumpsItsGenerationFirst() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot first = ledger.claim(1L, "world", "r0.0", 7, 4);
        ArenaSlot.Lease firstLease = first.lease(PLAN_EPOCH);
        long generation = firstLease.generation();
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(firstLease, true));
        assertEquals(generation + 1L, first.generation());
        assertEquals(ArenaSlot.State.FREE, first.state());
        assertFalse(first.publish(firstLease), "the old generation is no longer owned");

        ArenaSlot second = ledger.claim(2L, "world", "r0.0", 7, 8);
        assertEquals(first, second, "a free slot is reused");
        assertEquals(2L, second.ownerBatchId());
        assertEquals(1L, ledger.generationBumps());
    }

    @Test
    void aStaleLeaseCannotFreeTheSlotOfTheNextBatch() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(1L, "world", "r0.0", 7, 4);
        ArenaSlot.Lease first = slot.lease(PLAN_EPOCH);
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(first, true));

        ArenaSlot reused = ledger.claim(2L, "world", "r0.0", 7, 4);
        ArenaSlot.Lease second = reused.lease(PLAN_EPOCH);
        reused.scratch().reset(1);
        reused.scratch().write(0, 9.0, 9.0, 9.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);

        assertEquals(ArenaSlot.Release.STALE_LEASE, ledger.release(first, true),
            "a lease of the previous owner freed the slot of the next one");
        assertEquals(2L, reused.ownerBatchId(), "the late release changed the owner");
        assertNotEquals(ArenaSlot.State.FREE, reused.state(), "the late release freed the new slot");
        assertEquals(9.0, reused.scratch().posX(0), "the late release reset the new values");
        assertEquals(1L, ledger.staleReleases());
        assertEquals(0L, ledger.foreignWrites());

        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(second, true));
        assertTrue(ledger.pinPairsHold());
    }

    @Test
    void aSecondReleaseOfTheSameLeaseIsToldApartFromAStaleOne() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(1L, "world", "r0.0", 7, 2);
        ArenaSlot.Lease lease = slot.lease(PLAN_EPOCH);
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(lease, true));
        assertEquals(ArenaSlot.Release.ALREADY_RELEASED, ledger.release(lease, true),
            "a repeated release of one lease is a pairing, not a race");
        assertEquals(0L, ledger.staleReleases());
        assertEquals(1L, ledger.repeatReleases());
        assertEquals(1L, ledger.releases());
    }

    @Test
    void aLeaseOfAReturnedArenaIsRefusedInsteadOfCreatingASegment() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(1L, "world", "r0.0", 7, 2);
        ArenaSlot.Lease lease = slot.lease(PLAN_EPOCH);

        ledger.quarantineAll();

        assertEquals(ArenaSlot.Release.STALE_LEASE, ledger.release(lease, true));
        assertEquals(1L, ledger.quarantinedSlots());
        assertFalse(ledger.pinPairsHold(), "a quarantined pin is not a closed pin pair");
        assertEquals(1L, ledger.staleReleases());
    }

    @Test
    void aCancelledReleaseRetiresTheBufferForTheNextClaim() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSlot slot = ledger.claim(1L, "world", "r0.0", 7, 2);
        ArenaSlot.Lease first = slot.lease(PLAN_EPOCH);
        ArenaScratch firstBuffer = first.scratch();

        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(first, false));

        ArenaSlot reused = ledger.claim(2L, "world", "r0.0", 7, 2);
        assertNotEquals(firstBuffer, reused.lease(PLAN_EPOCH).scratch(),
            "a worker still in its body would write into the next batch's buffer");

        ArenaSlot.Lease second = reused.lease(PLAN_EPOCH);
        ArenaScratch secondBuffer = second.scratch();
        assertEquals(ArenaSlot.Release.RELEASED, ledger.release(second, true));
        ArenaSlot third = ledger.claim(3L, "world", "r0.0", 7, 2);
        assertEquals(secondBuffer, third.lease(PLAN_EPOCH).scratch(),
            "a confirmed release must hand its buffer back for reuse");
    }

    @Test
    void aCrossWorldClaimIsRefusedInsteadOfReusingTheSegment() {
        ArenaLedger ledger = new ArenaLedger();
        ArenaSegment first = ledger.segment("world-a", "r0.0", 7);
        ArenaSegment second = ledger.segment("world-b", "r0.0", 7);
        assertNotEquals(first.ref().key(), second.ref().key());
        ArenaSlot slot = ledger.claim(1L, "world-a", "r0.0", 7, 2);
        assertNotNull(slot);
        ArenaSlot.Lease lease = slot.lease(PLAN_EPOCH);
        assertEquals(ArenaSlot.Release.FOREIGN_SEGMENT, second.release(lease, true),
            "a slot must not be released into another world");
        assertEquals(ArenaSlot.Release.RELEASED, first.release(lease, true));
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
            ledger.release(slot.lease(PLAN_EPOCH), true);
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
        slot.forceRelease();
        ledger.reset();
        assertEquals(0L, ledger.claims());
        assertEquals(0L, ledger.releases());
        assertEquals(0, ledger.pinnedCount());
        assertTrue(ledger.pinPairsHold());
    }
}
