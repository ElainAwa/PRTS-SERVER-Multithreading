/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

/** A stable name for one slot at one generation. The reference is a value: comparing two of them
 * tells whether they name the same slot in the same generation. */
public record SlotRef(String worldId, String regionId, int segmentKind, int slotIndex,
                      long slotGeneration) {

    /** Validates the reference identity. */
    public SlotRef {
        if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
            throw new IllegalArgumentException("a slot reference needs a world and a region");
        }
    }
}
