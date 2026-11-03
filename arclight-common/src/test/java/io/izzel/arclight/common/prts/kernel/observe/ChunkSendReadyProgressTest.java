/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The send-ready part of the internal progress face: a real host notification counts once per
 * world/tick, and a duplicate chunk, stale epoch, unload or bound is refused without touching send. */
class ChunkSendReadyProgressTest {

    private final Map<String, Long> epochs = new HashMap<>();
    private final AtomicLong tick = new AtomicLong(10L);
    private final String world = "minecraft:overworld";

    @AfterEach
    void remove() {
        ChunkPipelineProgress.install(false, null, null);
    }

    private void arm() {
        epochs.put(world, 1L);
        ChunkPipelineProgress.install(true,
            id -> epochs.getOrDefault(id, WorldEpochs.UNTRACKED), tick::get);
    }

    @Test
    void sendReadyCountsPerWorldAndTick() {
        arm();
        ChunkPipelineProgress.noteSend(world, 1L, 4L);
        ChunkPipelineProgress.noteSend(world, 1L, 8L);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        ChunkPipelineProgress.WorldReading reading = snapshot.worlds().get(0);
        assertEquals(2L, reading.sendSteps());
        assertEquals(0L, reading.genSteps());
        assertEquals(0L, reading.lightSteps());
        assertEquals(2L, reading.ticks().get(0).sendSteps());
        assertEquals(2L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.COUNTED));
    }

    @Test
    void aRepeatedChunkIsRefusedWithoutTouchingTheSendPath() {
        arm();
        ChunkPipelineProgress.noteSend(world, 1L, 4L);
        ChunkPipelineProgress.noteSend(world, 1L, 4L);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertEquals(1L, snapshot.worlds().get(0).sendSteps());
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.DUPLICATE));
    }

    @Test
    void staleEpochAndUnloadAreRefused() {
        arm();
        ChunkPipelineProgress.noteSend(world, 1L, 4L);
        epochs.put(world, 2L);
        ChunkPipelineProgress.noteSend(world, 1L, 8L);
        ChunkPipelineProgress.forgetWorld(world);
        ChunkPipelineProgress.noteSend(world, 1L, 12L);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertTrue(snapshot.worlds().isEmpty());
        assertEquals(2L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.STALE_EPOCH));
    }

    @Test
    void sendReadyLeavesGenAndLightCountersAloneAndBoundsTicks() {
        arm();
        tick.set(0L);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, false,
            null, true);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, true,
            null, true);
        for (int index = 0; index < 200; index++) {
            tick.set(index);
            ChunkPipelineProgress.noteSend(world, 1L, index);
        }

        ChunkPipelineProgress.WorldReading reading = ChunkPipelineProgress.snapshot().worlds().get(0);
        assertEquals(1L, reading.genSteps());
        assertEquals(1L, reading.lightSteps());
        assertEquals(200L, reading.sendSteps());
        assertTrue(reading.ticks().size() <= ChunkPipelineProgress.MAX_TICK_GROUPS);
        assertEquals(128, reading.ticks().size());
    }
}
