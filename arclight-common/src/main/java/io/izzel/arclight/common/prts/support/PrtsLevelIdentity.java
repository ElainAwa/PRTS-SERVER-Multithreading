/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** Returns the only level for a world id, or null unless the count is exactly one. */
public final class PrtsLevelIdentity {

    private PrtsLevelIdentity() {
    }

    public static <T> T sole(String worldId, int count, T level) {
        if (worldId == null || worldId.isEmpty() || count != 1 || level == null) {
            return null;
        }
        return level;
    }
}
