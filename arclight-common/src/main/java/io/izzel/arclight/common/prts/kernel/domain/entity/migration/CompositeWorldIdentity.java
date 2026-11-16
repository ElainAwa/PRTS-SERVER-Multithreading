/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.migration;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.Objects;

/**
 * The composite identity of one migrated row: the world incarnation and the UUID pair, never the
 * numeric entity id, because a numeric id is only a per-world hint and is reused by the next
 * object. Two rows of one world incarnation and UUID pair are the same object even when the hint
 * differs; a new world incarnation makes the old identity stale.
 */
public record CompositeWorldIdentity(String worldId, long worldIncarnation, long uuidHigh,
                                     long uuidLow) {

    public CompositeWorldIdentity {
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("a composite identity needs its world");
        }
        if (worldIncarnation < 0L) {
            throw new IllegalArgumentException("a world incarnation cannot be negative");
        }
    }

    /** The composite identity of one frozen row. */
    public static CompositeWorldIdentity of(EntityRowIdentity identity, long worldIncarnation) {
        Objects.requireNonNull(identity, "identity");
        return new CompositeWorldIdentity(identity.worldId(), worldIncarnation,
            identity.uuidHigh(), identity.uuidLow());
    }

    /** The same object in a new world incarnation; the source incarnation must advance. */
    public CompositeWorldIdentity migratedTo(String targetWorldId, long targetIncarnation) {
        Objects.requireNonNull(targetWorldId, "targetWorldId");
        if (targetIncarnation <= worldIncarnation) {
            throw new IllegalArgumentException(
                "a migration needs a newer world incarnation, not " + targetIncarnation);
        }
        return new CompositeWorldIdentity(targetWorldId, targetIncarnation, uuidHigh, uuidLow);
    }

    /** Whether both identities name the same object (world incarnation and UUID pair). */
    public boolean sameObject(CompositeWorldIdentity other) {
        return other != null && worldId.equals(other.worldId)
            && worldIncarnation == other.worldIncarnation && uuidHigh == other.uuidHigh
            && uuidLow == other.uuidLow;
    }

    /** Whether the two identities belong to different world incarnations. */
    public boolean crossesWorld(CompositeWorldIdentity other) {
        return other != null && worldIncarnation != other.worldIncarnation;
    }
}
