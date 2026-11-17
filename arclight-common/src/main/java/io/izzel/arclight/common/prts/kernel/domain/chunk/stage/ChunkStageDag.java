/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.stage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The per-stage adjacency graph of one chunk: a stage runs only when its same-chunk previous stage
 * and its eight neighbours' previous stage are ready, a missing predecessor is an explicit waiting
 * answer, a stage keeps its pure-build lane or its main lane instead of leaving everything on main,
 * and a same-generation input revision only moves forward without clearing what is already ready. */
public final class ChunkStageDag {

    /** The generation stages in their fixed order. */
    public enum Stage {
        EMPTY,
        STRUCTURE_STARTS,
        STRUCTURE_REFERENCES,
        BIOMES,
        NOISE,
        SURFACE,
        CARVERS,
        FEATURES,
        LIGHT,
        SPAWN,
        FULL;

        /** Whether the stage is a pure build that may run off the main thread. */
        public boolean pureBuild() {
            return this == STRUCTURE_STARTS || this == STRUCTURE_REFERENCES || this == BIOMES
                || this == NOISE || this == SURFACE || this == CARVERS || this == FEATURES;
        }
    }

    /** Where a stage is allowed to run; the split keeps pure builds off main without moving all. */
    public enum Lane {
        WORKER,
        MAIN
    }

    /** One stage of one holder generation of one chunk at one frozen input revision. */
    public record Key(long worldIncarnation, long holderGen, long chunkPos, Stage stage,
                      long inputRevisionWitness) {

        public Key {
            Objects.requireNonNull(stage, "stage");
            if (worldIncarnation < 0L || holderGen < 0L || inputRevisionWitness < 0L) {
                throw new IllegalArgumentException("a stage key cannot name a negative generation");
            }
        }

        public Key withWitness(long witness) {
            return new Key(worldIncarnation, holderGen, chunkPos, stage, witness);
        }
    }

    /** The answer of one stage admission: ready, or waiting for the named predecessors only. */
    public record Admission(boolean ready, List<Key> missing) {

        public Admission {
            Objects.requireNonNull(missing, "missing");
        }
    }

    private final int neighbourRadius;
    private final Set<Key> ready = new java.util.LinkedHashSet<>();
    private final Map<Long, Long> revisions = new LinkedHashMap<>();
    private long refusedPredecessor;
    private long staleGenerations;

    /** One graph with the declared neighbour radius; only radius one has predecessors. */
    public ChunkStageDag(int neighbourRadius) {
        if (neighbourRadius < 0) {
            throw new IllegalArgumentException("a stage radius cannot be negative");
        }
        this.neighbourRadius = neighbourRadius;
    }

    /** The predecessors one stage needs: the same chunk's previous stage and its neighbours'. */
    public List<Key> predecessors(Key key) {
        if (key.stage() == Stage.EMPTY) {
            return List.of();
        }
        Stage previous = Stage.values()[key.stage().ordinal() - 1];
        List<Key> predecessors = new ArrayList<>(1 + (neighbourRadius * 2 + 1) * (neighbourRadius * 2 + 1) - 1);
        predecessors.add(new Key(key.worldIncarnation(), key.holderGen(), key.chunkPos(), previous,
            key.inputRevisionWitness()));
        if (neighbourRadius > 0) {
            int centerX = chunkX(key.chunkPos());
            int centerZ = chunkZ(key.chunkPos());
            for (int dx = -neighbourRadius; dx <= neighbourRadius; dx++) {
                for (int dz = -neighbourRadius; dz <= neighbourRadius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    predecessors.add(new Key(key.worldIncarnation(), key.holderGen(),
                        chunkPos(centerX + dx, centerZ + dz), previous,
                        key.inputRevisionWitness()));
                }
            }
        }
        return List.copyOf(predecessors);
    }

    /** Admits one stage: a missing predecessor is waiting, never a fabricated ready or empty. */
    public synchronized Admission admit(Key key) {
        Objects.requireNonNull(key, "key");
        List<Key> missing = new ArrayList<>();
        for (Key predecessor : predecessors(key)) {
            if (!ready.contains(predecessor)) {
                missing.add(predecessor);
            }
        }
        if (!missing.isEmpty()) {
            refusedPredecessor++;
            return new Admission(false, List.copyOf(missing));
        }
        ready.add(key);
        revisions.merge(key.holderGen(), key.inputRevisionWitness(), Math::max);
        return new Admission(true, List.of());
    }

    public synchronized boolean isReady(Key key) {
        return ready.contains(key);
    }

    /** Advances a same-generation input revision; a lower or equal revision never moves back. */
    public synchronized boolean advanceRevision(long holderGen, long revision) {
        Long current = revisions.get(holderGen);
        if (current != null && revision <= current) {
            staleGenerations++;
            return false;
        }
        revisions.put(holderGen, revision);
        return true;
    }

    /** The lane a stage runs on: pure builds are worker stages, the rest stay on main. */
    public static Lane laneOf(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        return stage.pureBuild() ? Lane.WORKER : Lane.MAIN;
    }

    /** Whether any declared stage is allowed off the main thread. */
    public static boolean hasWorkerLanes() {
        for (Stage stage : Stage.values()) {
            if (laneOf(stage) == Lane.WORKER) {
                return true;
            }
        }
        return false;
    }

    public synchronized int readyCount() {
        return ready.size();
    }

    public synchronized long refusedPredecessor() {
        return refusedPredecessor;
    }

    public synchronized long staleGenerations() {
        return staleGenerations;
    }

    /** Drops every ready stage and revision; test and evidence code only. */
    public synchronized void reset() {
        ready.clear();
        revisions.clear();
        refusedPredecessor = 0;
        staleGenerations = 0;
    }

    private static int chunkX(long chunkPos) {
        return (int) (chunkPos >> 32);
    }

    private static int chunkZ(long chunkPos) {
        return (int) chunkPos;
    }

    private static long chunkPos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
