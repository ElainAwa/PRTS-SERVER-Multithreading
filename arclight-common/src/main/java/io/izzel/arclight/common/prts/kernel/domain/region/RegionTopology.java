/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.region;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The eight-connected region topology: the loaded chunks are grouped by Chebyshev distance 1 into
 * components, each component carries a region id and a monotone topology generation, and the
 * generation advances only when two components really merge or one really splits; a plain readout
 * reset or a worker change never moves it. */
public final class RegionTopology {

    /** One connected component: its region id and the chunks it contains. */
    public record Region(String worldId, long regionId, Set<Long> chunks, long generation) {
    }

    /** What one load/unload did to the topology. */
    public enum Change {
        NONE,
        MERGED,
        SPLIT,
        GREW,
        SHRANK
    }

    private final String worldId;
    private final Map<Long, Long> regionOfChunk = new LinkedHashMap<>();
    private final Map<Long, Long> generations = new LinkedHashMap<>();
    private final Set<Long> chunks = new LinkedHashSet<>();

    public RegionTopology(String worldId) {
        this.worldId = worldId;
    }

    /** Loads one chunk; connecting two components merges them and bumps their generation. */
    public synchronized Change load(long chunkPos) {
        if (!chunks.add(chunkPos)) {
            return Change.NONE;
        }
        Map<Long, Long> before = new LinkedHashMap<>(regionOfChunk);
        Set<Long> distinctBefore = new LinkedHashSet<>(before.values());
        regroup();
        Set<Long> distinctAfter = new LinkedHashSet<>(regionOfChunk.values());
        Change change = distinctAfter.size() < distinctBefore.size() ? Change.MERGED : Change.GREW;
        if (change == Change.MERGED || change == Change.SPLIT || change == Change.GREW) {
            for (long region : distinctAfter) {
                generations.merge(region, 1L, Long::sum);
            }
        }
        return change;
    }

    /** Unloads one chunk; splitting one component into several bumps each new region. */
    public synchronized Change unload(long chunkPos) {
        if (!chunks.remove(chunkPos)) {
            return Change.NONE;
        }
        Set<Long> distinctBefore = new LinkedHashSet<>(regionOfChunk.values());
        regroup();
        Set<Long> distinctAfter = new LinkedHashSet<>(regionOfChunk.values());
        Change change = distinctAfter.size() > distinctBefore.size() ? Change.SPLIT : Change.SHRANK;
        if (change == Change.SPLIT || change == Change.SHRANK) {
            for (long region : distinctAfter) {
                generations.merge(region, 1L, Long::sum);
            }
        }
        return change;
    }

    /** The region of one chunk, or null when the chunk is not loaded. */
    public synchronized Long regionOf(long chunkPos) {
        return regionOfChunk.get(chunkPos);
    }

    /** The topology generation of one region; a reset of readings does not change it. */
    public synchronized long generationOf(long regionId) {
        return generations.getOrDefault(regionId, 0L);
    }

    /** The current components. */
    public synchronized List<Region> regions() {
        Map<Long, Set<Long>> groups = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> entry : regionOfChunk.entrySet()) {
            groups.computeIfAbsent(entry.getValue(), key -> new LinkedHashSet<>())
                .add(entry.getKey());
        }
        List<Region> regions = new ArrayList<>();
        for (Map.Entry<Long, Set<Long>> group : groups.entrySet()) {
            regions.add(new Region(worldId, group.getKey(), Set.copyOf(group.getValue()),
                generations.getOrDefault(group.getKey(), 0L)));
        }
        return List.copyOf(regions);
    }

    /** A readout reset is a plain observation and never a topology change. */
    public synchronized void resetReadout() {
    }

    private void regroup() {
        regionOfChunk.clear();
        Set<Long> unvisited = new LinkedHashSet<>(chunks);
        for (long seed : chunks) {
            if (!unvisited.contains(seed)) {
                continue;
            }
            Set<Long> component = new LinkedHashSet<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                long node = queue.poll();
                if (!unvisited.remove(node)) {
                    continue;
                }
                component.add(node);
                for (long neighbour : neighbours(node)) {
                    if (unvisited.contains(neighbour)) {
                        queue.add(neighbour);
                    }
                }
            }
            long regionId = component.stream().mapToLong(Long::longValue).min().orElse(seed);
            for (long chunk : component) {
                regionOfChunk.put(chunk, regionId);
            }
        }
    }

    private static List<Long> neighbours(long chunkPos) {
        int x = (int) (chunkPos >> 32);
        int z = (int) chunkPos;
        List<Long> neighbours = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                neighbours.add((((long) (x + dx)) << 32) | ((z + dz) & 0xFFFFFFFFL));
            }
        }
        return neighbours;
    }
}
