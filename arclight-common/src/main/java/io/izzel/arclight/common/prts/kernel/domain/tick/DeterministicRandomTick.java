/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.tick;

import io.izzel.arclight.common.prts.kernel.random.SharedStream;
import io.izzel.arclight.common.prts.kernel.random.StreamKey;
import io.izzel.arclight.common.prts.kernel.random.StreamState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The deterministic random-tick order: regions are visited in their region id order and the chunks
 * of one region in their chunk order, each chunk draws from its own stable shared stream whose
 * snapshot reproduces the next draws, so the chosen positions do not depend on the physical thread
 * that reached the chunk. */
public final class DeterministicRandomTick {

    /** One chunk of one region with its fixed position in the order. */
    public record ChunkOrder(long regionId, long chunkOrder, long chunkPos) {

        public ChunkOrder {
            if (regionId < 0L || chunkOrder < 0L) {
                throw new IllegalArgumentException("a random-tick order cannot be negative");
            }
        }
    }

    /** One drawn random tick of one chunk. */
    public record Draw(long regionId, long chunkOrder, long chunkPos, int bound, int value) {
    }

    private final long worldSeed;
    private final String worldIdentity;
    private final String dimension;
    private final Map<Long, SharedStream> streams = new LinkedHashMap<>();
    private long ticks;

    public DeterministicRandomTick(long worldSeed, String worldIdentity, String dimension) {
        this.worldSeed = worldSeed;
        this.worldIdentity = Objects.requireNonNull(worldIdentity, "worldIdentity");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    /** Draws one tick in fixed region/chunk order; the caller's list order is irrelevant. */
    public synchronized List<Draw> tick(List<ChunkOrder> chunks, int bound) {
        List<ChunkOrder> ordered = new ArrayList<>(chunks);
        ordered.sort(java.util.Comparator.comparingLong(ChunkOrder::regionId)
            .thenComparingLong(ChunkOrder::chunkOrder));
        List<Draw> draws = new ArrayList<>(ordered.size());
        for (ChunkOrder chunk : ordered) {
            SharedStream stream = streams.computeIfAbsent(chunk.chunkPos(),
                key -> new SharedStream(StreamKey.of(worldSeed, worldIdentity, dimension,
                    "random_tick", chunk.chunkPos())));
            draws.add(new Draw(chunk.regionId(), chunk.chunkOrder(), chunk.chunkPos(), bound,
                stream.nextInt(bound)));
        }
        ticks++;
        return List.copyOf(draws);
    }

    /** The stream snapshots of the drawn chunks, for the restore witness. */
    public synchronized Map<Long, StreamState> snapshot() {
        Map<Long, StreamState> state = new LinkedHashMap<>();
        for (Map.Entry<Long, SharedStream> stream : streams.entrySet()) {
            state.put(stream.getKey(), stream.getValue().snapshot());
        }
        return Map.copyOf(state);
    }

    /** Restores the recorded streams; the next draws continue instead of reseeding. */
    public synchronized void restore(Map<Long, StreamState> state) {
        Objects.requireNonNull(state, "state");
        streams.clear();
        for (Map.Entry<Long, StreamState> entry : state.entrySet()) {
            streams.put(entry.getKey(), SharedStream.restore(entry.getValue()));
        }
    }

    public synchronized long ticks() {
        return ticks;
    }
}
