/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the demand face makes of an ask, a miss, a future and a blocking call. */
class ChunkDemandObserverTest {

    private ChunkDemandObserver observer;

    @AfterEach
    void detach() {
        if (observer != null) {
            observer.detach();
        }
        PrtsChunkDemand.install(null);
    }

    @Test
    void nothingIsCountedWhileNoTapIsInstalled() {
        PrtsChunkDemand.install(null);
        assertFalse(PrtsChunkDemand.installed());
        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        assertEquals(-1L, PrtsChunkDemand.futureOpenedAt());
    }

    @Test
    void asksAnswersAndFuturesAreKeptApart() {
        observer = new ChunkDemandObserver();
        observer.attach();
        assertSame(observer, PrtsChunkDemand.watcher());
        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, true);
        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "full", false, false);

        assertEquals(2L, observer.requests());
        assertEquals(1L, observer.satisfied());
        assertEquals(1L, observer.missed());
        assertEquals("minecraft:overworld", observer.worldRows().get(0).key());
        assertEquals(2L, observer.worldRows().get(0).requests());
        assertEquals(2L, observer.statusRows().get(0).requests());
        assertEquals(1L, observer.statusRows().get(0).satisfied());

        PrtsChunkDemand.futureOpened("minecraft:overworld", "full");
        long stamp = PrtsChunkDemand.futureOpenedAt();
        assertTrue(stamp > 0L);
        PrtsChunkDemand.futureTaken("minecraft:overworld", "full");
        assertEquals(1L, observer.futuresInFlight());
        assertEquals(1, observer.futuresPeak());
        PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", stamp, true);
        assertEquals(0L, observer.futuresInFlight());
        assertEquals(1L, observer.futuresCompleted());
        assertEquals(1L, observer.futuresSatisfied());
        assertEquals(0L, observer.futuresFailed());
        assertEquals(0L, observer.futuresTaken() - observer.futuresCompleted());

        observer.detach();
        assertFalse(observer.installed());
        observer.reset();
        assertEquals(0L, observer.requests());
    }

    @Test
    void aBlockingAskLongerThanTheBoundKeepsItsCaller() throws InterruptedException {
        observer = new ChunkDemandObserver();
        observer.attach();
        PrtsChunkDemand.demandStarted("minecraft:overworld", "features", true);
        Thread.sleep(ChunkDemandObserver.LONG_DEMAND_NANOS / 1_000_000L + 20L);
        PrtsChunkDemand.demandFinished("minecraft:overworld", "features", true, true);

        assertEquals(1L, observer.blockingRequests());
        assertTrue(observer.blockingNanos() >= ChunkDemandObserver.LONG_DEMAND_NANOS);
        assertTrue(observer.blockingMaxNanos() >= ChunkDemandObserver.LONG_DEMAND_NANOS);
        List<ChunkDemandObserver.LongCall> calls = observer.longCalls();
        assertFalse(calls.isEmpty());
        assertTrue(calls.get(0).blocking());
        assertEquals("features", calls.get(0).status());
        assertTrue(calls.get(0).top() == null || calls.get(0).top().contains("."),
            "the caller stack is kept when the ask ran on the installing thread");
    }
}
