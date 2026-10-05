/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the chunk flow counters make of the two ends of a mailbox. */
class ChunkFlowObserverTest {

    private PrtsChunkFlow.FlowTap previous;

    @AfterEach
    void detach() {
        PrtsChunkFlow.install(previous);
    }

    @Test
    void nothingIsCountedWhileNoTapIsInstalled() {
        previous = PrtsChunkFlow.watcher();
        PrtsChunkFlow.install(null);
        assertFalse(PrtsChunkFlow.installed());
        PrtsChunkFlow.submitted("worldgen", 4);
        PrtsChunkFlow.completed("worldgen", 3);
    }

    @Test
    void theTwoEndsAndTheDepthAreKeptPerMailboxAndInTotal() {
        ChunkFlowObserver observer = new ChunkFlowObserver();
        observer.attach();
        assertSame(observer, PrtsChunkFlow.watcher());
        PrtsChunkFlow.submitted("worldgen", 3);
        PrtsChunkFlow.submitted("worldgen", 4);
        PrtsChunkFlow.completed("worldgen", 2);
        PrtsChunkFlow.submitted("light", 1);

        assertEquals(3L, observer.submitted());
        assertEquals(1L, observer.completed());
        assertEquals(2L, observer.submitted("worldgen"));
        assertEquals(1L, observer.completed("worldgen"));
        assertEquals(2, observer.depth("worldgen"));
        assertEquals(4, observer.depthPeak("worldgen"));
        assertEquals(3, observer.depth());
        assertEquals(4, observer.depthPeak());
        assertEquals("light,worldgen", String.join(",", observer.mailboxes()));
        assertTrue(observer.observedNanos() > 0L);
        observer.detach();
        assertFalse(PrtsChunkFlow.installed());
        observer.reset();
        assertEquals(0L, observer.submitted());
        assertEquals(0, observer.depthPeak());
        assertEquals("load_progress", observer.key("load-progress"));
    }

    @Test
    void aRenameOfTheCountersNeverLeavesAKeyOfTheReadoutUnsafe() {
        ChunkFlowObserver observer = new ChunkFlowObserver();
        assertEquals("unnamed", observer.key("unnamed"));
        assertEquals("a_b_c_d", observer.key("a.b:c/d"));
    }
}
