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
        assertEquals(1L, receipt.readId());
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
    void receiptKeepsLoadingAndMaterializationSources() {
        PeekReadService.ReadReceipt loading = PeekReadService.build(7L, 3L, "world", 1L, 4L, 9L,
            PeekBlockService.Status.LOADED, "chunk-now", 2L, true, true, "peek-result",
            List.of("ticket"), true, 2L, 1L, "chunk.demand", false);
        assertEquals(2L, loading.loadingCalls());
        assertEquals(1L, loading.newTickets());
        assertEquals("chunk.demand", loading.materializationSource());
        assertEquals(2L, loading.demand());
    }

    @Test
    void eventLossAndUnknownAreNotFilledWithZero() {
        PeekReadService.ReadReceipt loss = PeekReadService.build(8L, 0L, "world", 1L, 5L, 1L,
            PeekBlockService.Status.UNKNOWN, "chunk-absent", 0L, false, false, "none",
            List.of("UNKNOWN"), false, 0L, -1L, "UNKNOWN", true);
        assertTrue(loss.eventLoss());
        assertFalse(loss.ticketsKnown());
        assertEquals(-1L, loss.newTickets());
        assertEquals("UNKNOWN", loss.materializationSource());
        assertEquals("none", loss.holder());
    }
}
