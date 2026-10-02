/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

/**
 * A stable name for one slot at one generation.
 *
 * <p>The reference is a value: comparing two of them tells whether they name the same slot in the
 * same generation. A result carries the reference it wrote, and the merge can therefore refuse a
 * value that was produced for a generation that no longer exists.</p>
 *
 * @param worldId        the world the segment belongs to
 * @param regionId       the region the segment belongs to
 * @param segmentKind    the category of data the segment holds
 * @param slotIndex      the position of the slot inside its segment
 * @param slotGeneration the generation the reference was taken at
 */
public record SlotRef(String worldId, String regionId, int segmentKind, int slotIndex,
                      long slotGeneration) {

    /** Validates the reference identity. */
    public SlotRef {
        if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
            throw new IllegalArgumentException("a slot reference needs a world and a region");
        }
    }
}
