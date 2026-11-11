/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import java.util.HashMap;
import java.util.Map;

/** Pure grouping of loaded actor positions by chunk; no world access and no force load. */
final class AiCapturePlan {

    private AiCapturePlan() {
    }

    static long chunkKey(int blockX, int blockZ) {
        return (((long) (blockX >> 4)) << 32) ^ ((blockZ >> 4) & 0xffffffffL);
    }

    static Map<Long, Integer> group(int[] blockXs, int[] blockZs) {
        Map<Long, Integer> counts = new HashMap<>();
        for (int index = 0; index < blockXs.length; index++) {
            counts.merge(chunkKey(blockXs[index], blockZs[index]), 1, Integer::sum);
        }
        return counts;
    }

    static int emptyChunks(int chunksInRadius, int loadedChunksWithActors) {
        return Math.max(0, chunksInRadius - loadedChunksWithActors);
    }
}
