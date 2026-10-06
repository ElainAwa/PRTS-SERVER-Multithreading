/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the producer of the chunk row's declared progress signal counts, and what it refuses.
 *
 * <p>The producer is read through the same slot the row publishes, so what is read here is what an
 * export carries: an unbound slot, a bound slot that moved and a bound slot that did not are three
 * different states and none of them is allowed to look like another. The refusals are driven with
 * the events a broken producer would send - a completion sent twice, sent again in a later tick,
 * booked to a second world, and a value that goes backwards - because a producer that counts
 * whatever it is handed cannot be told apart from one that counts the right thing.
 */
class ChunkMaterializationObserverTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String THE_END = "minecraft:the_end";
    private static final String STATUS = PrtsChunkMaterialization.STATUS_FULL;

    private final ChunkMaterializationObserver producer = new ChunkMaterializationObserver();

    @AfterEach
    void handTheSeamBack() {
        producer.detach();
        PrtsChunkMaterialization.install(null);
    }

    private static WaitProgress progress() {
        return new WaitPointRegistry(() -> 50, () -> false).progress();
    }

    @Test
    void anUnboundSlotPublishesNoReadingAndTheStateIsNotExecutable() {
        WaitProgress.Reading unbound = progress().read("chunk");
        assertFalse(unbound.bound(), "the shipped default must leave the row unbound");
        assertEquals("unbound", unbound.source());
        assertEquals(0L, unbound.value());
        assertEquals(0L, unbound.delta());
        assertEquals(ChunkMaterializationObserver.LedgerState.NOT_EXECUTABLE, producer.read().state());
    }

    @Test
    void aBoundSlotReadsTheProducersValueThroughTheRow() {
        WaitProgress row = progress();
        row.bind("chunk", PrtsChunkMaterialization.SOURCE, producer::value);
        producer.attach();
        producer.accept(1L, OVERWORLD, 4L, STATUS, 1, 10L);
        WaitProgress.Reading reading = row.read("chunk");
        assertTrue(reading.bound());
        assertEquals(PrtsChunkMaterialization.SOURCE, reading.source());
        assertEquals(1L, reading.value(), "the row must read the producer and nothing else");
        assertEquals(1L, reading.delta());
        row.unbind("chunk");
        WaitProgress.Reading off = row.read("chunk");
        assertFalse(off.bound());
        assertEquals("unbound", off.source());
        assertEquals(0L, off.value());
    }

    @Test
    void aBoundProducerThatMaterializedNothingIsStillNotExecutable() {
        producer.attach();
        ChunkMaterializationObserver.Reading reading = producer.read();
        assertTrue(reading.bound());
        assertEquals(0L, reading.value());
        assertEquals(ChunkMaterializationObserver.LedgerState.NOT_EXECUTABLE, reading.state(),
            "a run in which nothing was materialized is refused, not reported as a zero");
    }

    @Test
    void aBoundProducerThatMovedThenStoodStillIsAReadableZero() {
        producer.attach();
        producer.accept(1L, OVERWORLD, 4L, STATUS, 1, 10L);
        ChunkMaterializationObserver.Reading moved = producer.read();
        assertEquals(1L, moved.value());
        assertEquals(1L, moved.delta());
        assertEquals(ChunkMaterializationObserver.LedgerState.PROGRESS_SIGNAL, moved.state());
        ChunkMaterializationObserver.Reading still = producer.read();
        assertEquals(0L, still.delta());
        assertEquals(ChunkMaterializationObserver.LedgerState.PROGRESS_ZERO, still.state(),
            "a bound producer that was touched and did not move is a readable zero");
    }

    @Test
    void theSameCompletionReportedTwiceInsideItsTickIsCountedOnce() {
        producer.attach();
        assertEquals(ChunkMaterializationObserver.Outcome.COUNTED,
            producer.accept(7L, OVERWORLD, 4L, STATUS, 1, 10L));
        assertEquals(ChunkMaterializationObserver.Outcome.DUPLICATE,
            producer.accept(7L, OVERWORLD, 4L, STATUS, 1, 10L));
        assertEquals(1L, producer.value(), "the same completion must not be counted twice");
        assertEquals(1L, producer.duplicates());
    }

    @Test
    void theSameCompletionReportedInALaterTickIsRefused() {
        producer.attach();
        assertEquals(ChunkMaterializationObserver.Outcome.COUNTED,
            producer.accept(1L, OVERWORLD, 4L, STATUS, 1, 10L));
        assertEquals(ChunkMaterializationObserver.Outcome.CROSS_TICK,
            producer.accept(2L, OVERWORLD, 4L, STATUS, 1, 11L));
        assertEquals(1L, producer.value());
        assertEquals(1L, producer.crossTick());
        assertFalse(producer.violations().isEmpty(), "a mis-attributed tick must be named");
    }

    @Test
    void aCompletionBookedToASecondWorldIsRefused() {
        producer.attach();
        assertEquals(ChunkMaterializationObserver.Outcome.COUNTED,
            producer.accept(9L, OVERWORLD, 4L, STATUS, 1, 10L));
        assertEquals(ChunkMaterializationObserver.Outcome.CROSS_WORLD,
            producer.accept(9L, THE_END, 8L, STATUS, 1, 10L));
        assertEquals(1L, producer.value());
        assertEquals(1L, producer.crossWorld());
        assertFalse(producer.violations().isEmpty(), "a mis-attributed world must be named");
    }

    @Test
    void aProducerThatGoesBackwardsIsRefused() {
        assertTrue(producer.settle(5L));
        assertFalse(producer.settle(3L), "a published value must never fall");
        assertEquals(1L, producer.nonMonotonic());
        assertEquals(5L, producer.lastPublished());
        assertFalse(producer.violations().isEmpty());
    }

    @Test
    void theValueIsGroupedByWorldAndByTick() {
        producer.attach();
        producer.accept(1L, OVERWORLD, 1L, STATUS, 1, 10L);
        producer.accept(2L, OVERWORLD, 2L, STATUS, 1, 10L);
        producer.accept(3L, OVERWORLD, 3L, STATUS, 1, 12L);
        producer.accept(4L, THE_END, 1L, STATUS, 1, 11L);
        assertEquals(2, producer.worlds().size());
        assertEquals(3L, producer.worldTotal(OVERWORLD));
        assertEquals(1L, producer.worldTotal(THE_END));
        Map<Long, Long> overworld = producer.tickGroups(OVERWORLD);
        assertEquals(2L, overworld.get(10L));
        assertEquals(1L, overworld.get(12L));
        assertEquals(1L, producer.tickGroups(THE_END).get(11L));
        ChunkMaterializationObserver.Event last = producer.lastEvent();
        assertNotNull(last);
        assertEquals(STATUS, last.status());
        assertEquals(11L, last.tick());
        assertEquals(1, last.generation());
        assertEquals(PrtsChunkMaterialization.SOURCE, last.source());
    }

    @Test
    void theSeamReachesOnlyTheInstalledProducer() {
        producer.detach();
        PrtsChunkMaterialization.materialized(11L, OVERWORLD, 4L, STATUS, 1);
        assertEquals(0L, producer.value(), "a detached producer must not be reached");
        producer.attach();
        PrtsChunkMaterialization.materialized(11L, OVERWORLD, 4L, STATUS, 1);
        assertEquals(1L, producer.value());
        ChunkMaterializationObserver.Reading reading = producer.read();
        assertEquals(ChunkMaterializationObserver.LedgerState.PROGRESS_SIGNAL, reading.state());
        assertEquals(1L, reading.events());
    }
}
