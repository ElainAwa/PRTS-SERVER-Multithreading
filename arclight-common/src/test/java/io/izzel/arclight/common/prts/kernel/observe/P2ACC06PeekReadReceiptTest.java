/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit peek receipts are immutable, bounded, and keep unknown sources UNKNOWN; a missing level
 * exercises the real public peek fallback without loading anything. */
class P2ACC06PeekReadReceiptTest {

    private static final BlockPos POS = new BlockPos(10, 64, 10);

    @AfterEach
    void cleanup() {
        PeekReadService.reset();
        PeekBlockService.reset();
        MaterializedUnloadedReadings.reset();
    }

    @Test
    void nullLevelUsesPublicPeekFallbackAndUnknownTickets() {
        Object fallback = new Object();
        PeekReadService.ReadReceipt receipt = PeekReadService.read(null, POS, fallback);
        assertEquals(PeekBlockService.Status.NO_WORLD, receipt.status());
        assertFalse(receipt.ticketsKnown());
        assertEquals(List.of("UNKNOWN"), receipt.tickets());
        assertEquals(-1L, receipt.newTickets());
        assertFalse(receipt.loadedBefore());
        assertFalse(receipt.loadedAfter());
        assertEquals(0L, receipt.loadingCalls());
        assertEquals("none", receipt.materializationSource());
        assertEquals("source-unavailable", receipt.ticketSource());
        assertEquals(1L, receipt.readId());
    }

    @Test
    void managedPeekWindowCompletesAndPublishesZero() {
        MaterializedUnloadedReadings.beginWindow(1);
        PeekReadService.ReadReceipt first = PeekReadService.read(null, POS, null);
        assertEquals(PeekBlockService.Status.NO_WORLD, first.status());
        assertEquals(java.util.OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
        assertEquals(0L, MaterializedUnloadedReadings.publishedValue().orElse(-1L));

        MaterializedUnloadedReadings.beginWindow(1);
        PeekReadService.ReadReceipt second = PeekReadService.read(null, POS, null);
        assertEquals(PeekBlockService.Status.NO_WORLD, second.status());
        assertEquals(java.util.OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
        assertFalse(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void actualMaterializationPublishesPositiveAndFails() {
        MaterializedUnloadedReadings.beginWindow(1);
        long readId = MaterializedUnloadedReadings.begin("world", 42L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("world", 42L, true, true, true);
        MaterializedUnloadedReadings.onSynchronousRead("world", 42L, true, true, true);
        MaterializedUnloadedReadings.end(readId, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(java.util.OptionalLong.of(1L), MaterializedUnloadedReadings.publishedValue());
        assertTrue(MaterializedUnloadedReadings.failOnPositive());

        MaterializedUnloadedReadings.reset();
        MaterializedUnloadedReadings.beginWindow(0);
        assertEquals(java.util.OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
        assertFalse(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void ringIsBoundedAndReadIdsAdvance() {
        for (int index = 0; index < 257; index++) {
            PeekReadService.read(null, POS, null);
        }
        assertEquals(256, PeekReadService.ring().size());
        assertEquals(1L, PeekReadService.overflow());
        assertTrue(PeekReadService.ring().get(255).readId() > PeekReadService.ring().get(0).readId());
    }

    @Test
    void epochAdvanceChangesReceiptEpoch() {
        PeekReadService.ReadReceipt first = PeekReadService.read(null, POS, null);
        PeekReadService.advanceEpoch();
        PeekReadService.ReadReceipt second = PeekReadService.read(null, POS, null);
        assertEquals(0L, first.epoch());
        assertEquals(1L, second.epoch());
    }

    @Test
    void ticketDecisionKeepsKnownEmptyAndSeparatesIntent() {
        PeekReadService.TicketView emptyLoaded = PeekReadService.ticketDecision(true, true,
            List.of(), true, true, false);
        assertTrue(emptyLoaded.known());
        assertEquals(List.of(), emptyLoaded.active());
        assertEquals("known-empty", emptyLoaded.source());

        PeekReadService.TicketView active = PeekReadService.ticketDecision(true, true,
            List.of("ticket"), true, true, true);
        assertTrue(active.known());
        assertEquals(List.of("ticket"), active.active());
        assertTrue(active.forceloadIntent());
        assertEquals("active", active.source());

        PeekReadService.TicketView unreadable = PeekReadService.ticketDecision(true, true,
            List.of("ticket"), false, true, true);
        assertFalse(unreadable.known());
        assertEquals(List.of("UNKNOWN"), unreadable.active());
        assertEquals("source-unavailable", unreadable.source());

        PeekReadService.TicketView cold = PeekReadService.ticketDecision(true, false,
            List.of(), true, true, false);
        PeekReadService.TicketView offThread = PeekReadService.ticketDecision(false, true,
            List.of("ticket"), true, true, true);
        assertFalse(offThread.known());
        assertEquals("thread-unavailable", offThread.source());
        assertTrue(cold.known());
        assertEquals(List.of(), cold.active());
    }

    @Test
    void setDifferenceCountsNewIdentitiesOnly() {
        assertEquals(1L, PeekReadService.setDifference(List.of("a"), List.of()));
        assertEquals(0L, PeekReadService.setDifference(List.of("b", "a"), List.of("a", "b")));
        assertEquals(1L, PeekReadService.setDifference(List.of("b"), List.of("a")));
        assertEquals(1L, PeekReadService.setDifference(List.of("a", "a"), List.of()));
        assertEquals(0L, PeekReadService.setDifference(List.of(), List.of("a")));
    }

    @Test
    void receiptKeepsLoadingAndMaterializationSources() {
        PeekReadService.ReadReceipt loading = PeekReadService.build(7L, 3L, "world", 1L, 4L, 9L,
            PeekBlockService.Status.LOADED, "chunk-now", 2L, true, true, "peek-result",
            List.of("ticket"), true, 2L, 1L, "chunk.demand", false, false, "active");
        assertEquals(2L, loading.loadingCalls());
        assertEquals(1L, loading.newTickets());
        assertEquals("chunk.demand", loading.materializationSource());
        assertEquals(2L, loading.demand());
    }

    @Test
    void eventLossAndUnknownAreNotFilledWithZero() {
        PeekReadService.ReadReceipt loss = PeekReadService.build(8L, 0L, "world", 1L, 5L, 1L,
            PeekBlockService.Status.UNKNOWN, "chunk-absent", 0L, false, false, "none",
            List.of("UNKNOWN"), false, 0L, -1L, "UNKNOWN", true, false, "source-unavailable");
        assertTrue(loss.eventLoss());
        assertFalse(loss.ticketsKnown());
        assertEquals(-1L, loss.newTickets());
        assertEquals("UNKNOWN", loss.materializationSource());
        assertEquals("none", loss.holder());
    }
}
