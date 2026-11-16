/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.Objects;

/**
 * The full identity of one entity row of one tick: the world incarnation, the object lifecycle (the
 * UUID pair), the numeric id as a reusable hint and the update ordinal as a witness. Two rows name
 * the same object only when the incarnation and both UUID halves match.
 */
public record EntityRowIdentity(String worldId, long worldEpoch, long uuidHigh, long uuidLow,
                                int entityId, int tickCount) {

    public EntityRowIdentity {
        if (worldId == null || worldId.isEmpty()) {
            throw new IllegalArgumentException("a row identity needs a world");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("a row identity needs a world generation");
        }
    }

    /** The object lifecycle key: the world incarnation and the UUID pair, never the numeric id. */
    public ObjectKey objectKey() {
        return new ObjectKey(worldId, worldEpoch, uuidHigh, uuidLow);
    }

    /** Whether both rows name the same object, whatever their ids and witnesses say. */
    public boolean sameObject(EntityRowIdentity other) {
        return other != null && objectKey().equals(other.objectKey());
    }

    /** Whether the row carries a host update ordinal, or was declared by a fixture instead. */
    public boolean witnessed() {
        return tickCount >= 0;
    }

    /** A declared row: it names only the numeric id inside one world incarnation. */
    public static EntityRowIdentity fixture(String worldId, long worldEpoch, int entityId) {
        return new EntityRowIdentity(worldId, worldEpoch, 0L, 0L, entityId, -1);
    }

    /** The object lifecycle key of one row. */
    public record ObjectKey(String worldId, long worldEpoch, long uuidHigh, long uuidLow) {

        public ObjectKey {
            Objects.requireNonNull(worldId, "worldId");
            if (worldId.isEmpty()) {
                throw new IllegalArgumentException("an object key needs a world");
            }
        }
    }
}
