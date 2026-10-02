/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

/**
 * The identity of one arena segment: a category of intermediate state of one region of one world.
 *
 * <p>A segment is never shared across worlds: the same region name in another world is another
 * segment, and a claim that names a different world is refused rather than reusing the buffer.</p>
 *
 * @param worldId     the world the segment belongs to
 * @param regionId    the region the segment belongs to
 * @param segmentKind the category of data the segment holds
 */
public record SegmentRef(String worldId, String regionId, int segmentKind) {

    /** Validates the segment identity. */
    public SegmentRef {
        if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
            throw new IllegalArgumentException("a segment needs a world and a region");
        }
    }

    /** @return the stable key this segment is stored under */
    public String key() {
        return worldId + "|" + regionId + "|" + segmentKind;
    }
}
