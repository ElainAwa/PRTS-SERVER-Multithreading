/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

/** Pure 16-block cell index used by capture; no world access. */
final class AiSpatialGrid {

    static final int CELL = 16;

    private AiSpatialGrid() {
    }

    static long cellKey(int blockX, int blockZ) {
        return (((long) (blockX >> 4)) << 32) ^ ((blockZ >> 4) & 0xffffffffL);
    }

    static long[] around(int blockX, int blockZ) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        long[] keys = new long[9];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                keys[index++] = (((long) (cx + dx)) << 32) ^ ((cz + dz) & 0xffffffffL);
            }
        }
        return keys;
    }

    static boolean inRange(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) <= CELL && Math.abs(az - bz) <= CELL;
    }
}
