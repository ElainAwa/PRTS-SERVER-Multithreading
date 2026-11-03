/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal per-world progress face: real future callbacks count successful steps, and a
 * failure, cancellation, duplicate, stale epoch, unknown world or full bound is refused without
 * reaching the future. */
class ChunkPipelineProgressTest {

    private final Map<String, Long> epochs = new HashMap<>();
    private final AtomicLong tick = new AtomicLong(10L);
    private final String world = "minecraft:overworld";

    @AfterEach
    void remove() {
        ChunkPipelineProgress.install(false, null, null);
    }

    private void arm() {
        epochs.put(world, 1L);
        epochs.put("minecraft:the_end", 2L);
        ChunkPipelineProgress.install(true,
            id -> epochs.getOrDefault(id, WorldEpochs.UNTRACKED), tick::get);
    }

    private long success(String worldId, long epoch, boolean light) {
        long ticket = ChunkPipelineProgress.nextTicket();
        ChunkPipelineProgress.note(ticket, worldId, epoch, 0L, light, null, true);
        return ticket;
    }

    @Test
    void disabledFaceCountsNothing() {
        ChunkPipelineProgress.install(false,
            id -> epochs.getOrDefault(id, WorldEpochs.UNTRACKED), tick::get);
        success(world, 1L, false);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertFalse(snapshot.installed());
        assertTrue(snapshot.worlds().isEmpty());
        assertEquals(0L, snapshot.outcomes().getOrDefault(ChunkPipelineProgress.Outcome.COUNTED, 0L));
    }

    @Test
    void realFutureCallbacksCountGenAndLightPerWorldAndTick() {
        arm();
        CompletableFuture<String> gen = new CompletableFuture<>();
        CompletableFuture<String> light = new CompletableFuture<>();
        gen.whenComplete((result, failure) -> ChunkPipelineProgress.note(
            ChunkPipelineProgress.nextTicket(), world, 1L, 4L, false, failure, result != null));
        light.whenComplete((result, failure) -> ChunkPipelineProgress.note(
            ChunkPipelineProgress.nextTicket(), world, 1L, 8L, true, failure, result != null));
        gen.complete("chunk");
        light.complete("chunk");

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        ChunkPipelineProgress.WorldReading reading = snapshot.worlds().get(0);
        assertEquals(world, reading.worldId());
        assertEquals(1L, reading.epoch());
        assertEquals(1L, reading.genSteps());
        assertEquals(1L, reading.lightSteps());
        assertEquals(1L, reading.ticks().get(0).genSteps());
        assertEquals(1L, reading.ticks().get(0).lightSteps());
        assertEquals(2L, snapshot.outcomes().getOrDefault(
            ChunkPipelineProgress.Outcome.COUNTED, 0L));
    }

    @Test
    void failureCancellationAndMissingResultAreRefused() {
        arm();
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, false,
            new IllegalStateException("failed"), false);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, false,
            new CancellationException("cancelled"), false);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, true,
            null, false);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertTrue(snapshot.worlds().isEmpty());
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.FAILED));
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.CANCELLED));
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.MISSING_RESULT));
    }

    @Test
    void aRepeatedTicketIsRefusedInsideTheBoundedWindow() {
        arm();
        long ticket = success(world, 1L, false);
        ChunkPipelineProgress.note(ticket, world, 1L, 0L, false, null, true);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertEquals(1L, snapshot.worlds().get(0).genSteps());
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.DUPLICATE));
    }

    @Test
    void staleAndUntrackedEpochsAreRefused() {
        arm();
        success(world, 1L, false);
        epochs.put(world, 5L);
        success(world, 1L, false);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), "minecraft:unknown", 0L,
            0L, false, null, true);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, WorldEpochs.UNTRACKED,
            0L, false, null, true);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertEquals(1L, snapshot.worlds().get(0).genSteps());
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.STALE_EPOCH));
        assertEquals(2L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.UNTRACKED_EPOCH));
    }

    @Test
    void anUnloadedWorldDropsItsRetainedProgressAndLateReportsAreStale() {
        arm();
        success(world, 1L, false);
        ChunkPipelineProgress.forgetWorld(world);
        epochs.put(world, WorldEpochs.UNLIVE);
        success(world, 1L, false);

        ChunkPipelineProgress.Snapshot snapshot = ChunkPipelineProgress.snapshot();
        assertTrue(snapshot.worlds().isEmpty());
        assertEquals(1L, snapshot.outcomes().get(ChunkPipelineProgress.Outcome.STALE_EPOCH));
    }

    @Test
    void tickGroupsAreBoundedAndOldTicksAreStale() {
        arm();
        for (long value = 0L; value < 200L; value++) {
            tick.set(value);
            success(world, 1L, false);
        }
        ChunkPipelineProgress.WorldReading reading = ChunkPipelineProgress.snapshot().worlds().get(0);
        assertTrue(reading.ticks().size() <= ChunkPipelineProgress.MAX_TICK_GROUPS);
        assertEquals(128, reading.ticks().size());
        assertEquals(72L, reading.ticks().get(0).tick());

        tick.set(0L);
        ChunkPipelineProgress.note(ChunkPipelineProgress.nextTicket(), world, 1L, 0L, false, null,
            true);
        assertEquals(1L, ChunkPipelineProgress.snapshot().outcomes()
            .get(ChunkPipelineProgress.Outcome.STALE_TICK));
    }

    @Test
    void worldsAreBoundedAndResetClearsRetainedCounts() {
        arm();
        for (int index = 0; index < ChunkPipelineProgress.MAX_WORLDS + 1; index++) {
            String id = "minecraft:world_" + index;
            epochs.put(id, 1L);
            success(id, 1L, false);
        }
        ChunkPipelineProgress.Snapshot bounded = ChunkPipelineProgress.snapshot();
        assertEquals(ChunkPipelineProgress.MAX_WORLDS, bounded.worlds().size());
        assertEquals(1L, bounded.outcomes().get(ChunkPipelineProgress.Outcome.WORLD_CAPACITY));

        ChunkPipelineProgress.reset();
        ChunkPipelineProgress.Snapshot cleared = ChunkPipelineProgress.snapshot();
        assertTrue(cleared.worlds().isEmpty());
        assertTrue(cleared.installed());
        assertEquals(0L, cleared.outcomes().getOrDefault(
            ChunkPipelineProgress.Outcome.COUNTED, 0L));
    }
}
