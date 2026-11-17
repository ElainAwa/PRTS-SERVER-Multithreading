/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import java.util.EnumSet;
import java.util.Objects;

/**
 * The completion state of one chunk under one holder generation: the eight layers are marked
 * separately, so loaded and entity-ticking are two different facts, and a result that names an
 * older holder generation is refused instead of advancing the newer one. A new generation starts
 * with no layer reached.
 */
public final class ChunkCompletionState {

    /** What one mark did. */
    public enum MarkResult {
        ADVANCED,
        DUPLICATE,
        STALE_HOLDER
    }

    /** The immutable view of one state. */
    public record Snapshot(long holderGen, EnumSet<ChunkCompletionLayer> reached) {

        public Snapshot {
            reached = reached.isEmpty() ? EnumSet.noneOf(ChunkCompletionLayer.class)
                : EnumSet.copyOf(reached);
        }

        /** Whether one layer or a later one was reached. */
        public boolean atLeast(ChunkCompletionLayer layer) {
            for (ChunkCompletionLayer reachedLayer : reached) {
                if (reachedLayer.atLeast(layer)) {
                    return true;
                }
            }
            return false;
        }
    }

    private long holderGen = -1L;
    private final EnumSet<ChunkCompletionLayer> reached =
        EnumSet.noneOf(ChunkCompletionLayer.class);
    private int staleResults;

    /** Marks one layer of one holder generation; an older generation never advances the state. */
    public synchronized MarkResult mark(long holderGen, ChunkCompletionLayer layer) {
        Objects.requireNonNull(layer, "layer");
        if (this.holderGen >= 0L && holderGen < this.holderGen) {
            staleResults++;
            return MarkResult.STALE_HOLDER;
        }
        if (holderGen > this.holderGen) {
            this.holderGen = holderGen;
            reached.clear();
        }
        if (this.holderGen < 0L) {
            this.holderGen = holderGen;
        }
        if (reached.add(layer)) {
            return MarkResult.ADVANCED;
        }
        return MarkResult.DUPLICATE;
    }

    /** Whether the holder generation was loaded at least once. */
    public synchronized boolean loaded() {
        return reached.contains(ChunkCompletionLayer.LOADED);
    }

    /** Whether the holder generation has entered entity ticking; loaded alone is not this. */
    public synchronized boolean entityTicking() {
        return reached.contains(ChunkCompletionLayer.ENTITY_TICKING);
    }

    /** Whether every layer up to and including the given one was reached. */
    public synchronized boolean completeThrough(ChunkCompletionLayer layer) {
        for (ChunkCompletionLayer candidate : ChunkCompletionLayer.values()) {
            if (!reached.contains(candidate)) {
                return false;
            }
            if (candidate == layer) {
                return true;
            }
        }
        return false;
    }

    /** Whether the holder generation reached the final acknowledgement. */
    public synchronized boolean acknowledged() {
        return reached.contains(ChunkCompletionLayer.ACK);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(holderGen, reached);
    }

    /** Results refused because their holder generation was older than the current one. */
    public synchronized int staleResults() {
        return staleResults;
    }
}
