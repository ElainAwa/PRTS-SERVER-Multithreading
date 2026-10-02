/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

public record SlotRef(String worldId, String regionId, int segmentKind, int slotIndex,
                      long slotGeneration) {

    public SlotRef {
        if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
            throw new IllegalArgumentException("a slot reference needs a world and a region");
        }
    }
}
