/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.observe.ChunkPipelineProgress;
import io.izzel.arclight.common.prts.kernel.waitpoints.Dec19Elements;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conservative min watermark of the existing gen row: one stalled world holds the value,
 * absent worlds unbind, and only a common advance moves the published reading. */
class GenMinWatermarkTest {

    private static final String A = "minecraft:overworld";
    private static final String B = "minecraft:the_end";

    private static WaitProgress progress() {
        WaitProgress progress = new WaitProgress();
        progress.declare("gen", new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT,
            "progress.gen.completed_segments"));
        return progress;
    }

    private static ChunkPipelineProgress.WorldReading world(String id, long epoch, long gen) {
        return new ChunkPipelineProgress.WorldReading(id, epoch, gen, 0L, 0L, List.of());
    }

    @Test
    void aSingleWorldPublishesItsPositiveValueAndDelta() {
        WaitProgress progress = progress();
        GenMinWatermark watermark = new GenMinWatermark();
        watermark.sync(progress, true, List.of(world(A, 1L, 2L)));

        WaitProgress.Reading first = progress.read("gen");
        assertTrue(first.bound());
        assertEquals(2L, first.value());
        assertEquals(2L, first.delta());
        assertEquals(GenMinWatermark.SOURCE, first.source());

        watermark.sync(progress, true, List.of(world(A, 1L, 5L)));
        WaitProgress.Reading second = progress.read("gen");
        assertEquals(5L, second.value());
        assertEquals(3L, second.delta());
    }

    @Test
    void twoWorldsUseTheMinimumAndOneStalledWorldHoldsIt() {
        WaitProgress progress = progress();
        GenMinWatermark watermark = new GenMinWatermark();
        watermark.sync(progress, true, List.of(world(A, 1L, 3L), world(B, 1L, 4L)));
        WaitProgress.Reading first = progress.read("gen");
        assertEquals(3L, first.value());
        assertEquals(3L, first.delta());

        watermark.sync(progress, true, List.of(world(A, 1L, 3L), world(B, 1L, 6L)));
        WaitProgress.Reading stalled = progress.read("gen");
        assertEquals(3L, stalled.value());
        assertEquals(0L, stalled.delta(), "B advanced but the stalled A holds the minimum");

        watermark.sync(progress, true, List.of(world(A, 1L, 4L), world(B, 1L, 6L)));
        WaitProgress.Reading moved = progress.read("gen");
        assertEquals(4L, moved.value());
        assertEquals(1L, moved.delta());
    }

    @Test
    void anUnloadedWorldUnbindsAndTheNextBindingStartsAFreshWatermark() {
        WaitProgress progress = progress();
        GenMinWatermark watermark = new GenMinWatermark();
        watermark.sync(progress, true, List.of(world(A, 1L, 3L), world(B, 1L, 4L)));
        assertTrue(progress.read("gen").bound());

        watermark.sync(progress, true, List.of(world(B, 1L, 4L)));
        assertFalse(progress.read("gen").bound(), "the world set changed; the row is unbound");

        watermark.sync(progress, true, List.of(world(B, 1L, 4L)));
        WaitProgress.Reading rebound = progress.read("gen");
        assertTrue(rebound.bound());
        assertEquals(4L, rebound.value());
        assertEquals(4L, rebound.delta(), "a fresh binding starts a fresh watermark");
    }

    @Test
    void anEpochChangeUnbindsAndDoesNotCrossTheOldGeneration() {
        WaitProgress progress = progress();
        GenMinWatermark watermark = new GenMinWatermark();
        watermark.sync(progress, true, List.of(world(A, 1L, 3L)));
        assertTrue(progress.read("gen").bound());

        watermark.sync(progress, true, List.of(world(A, 2L, 3L)));
        assertFalse(progress.read("gen").bound(), "a new epoch must not reuse the old watermark");

        watermark.sync(progress, true, List.of(world(A, 2L, 3L)));
        WaitProgress.Reading rebound = progress.read("gen");
        assertTrue(rebound.bound());
        assertEquals(3L, rebound.value());
    }

    @Test
    void noTrackedWorldAndAClosedProducerLeaveTheRowUnbound() {
        WaitProgress progress = progress();
        GenMinWatermark watermark = new GenMinWatermark();
        watermark.sync(progress, true, List.of());
        assertFalse(progress.read("gen").bound());

        watermark.sync(progress, true, List.of(world(A, 1L, 3L)));
        assertTrue(progress.read("gen").bound());
        watermark.sync(progress, false, List.of(world(A, 1L, 3L)));
        assertFalse(progress.read("gen").bound());
        watermark.sync(progress, true, List.of(world(A, 1L, 3L)));
        assertTrue(progress.read("gen").bound(), "a fresh producer binds again");
        watermark.clear(progress);
        assertFalse(progress.read("gen").bound());
    }
}
