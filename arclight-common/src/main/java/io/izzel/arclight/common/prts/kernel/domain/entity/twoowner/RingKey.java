/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import java.util.Objects;

/** The ring key of one owner lane: the world incarnation and the domain the ring carries. Two
 * owners never write the same global metadata key at once; the key is what keeps them apart. */
public record RingKey(String worldId, long worldIncarnation, String domainId)
    implements Comparable<RingKey> {

    public RingKey {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(domainId, "domainId");
        if (worldId.isEmpty() || domainId.isEmpty()) {
            throw new IllegalArgumentException("a ring key needs its world and its domain");
        }
        if (worldIncarnation < 0L) {
            throw new IllegalArgumentException("a ring key cannot name a negative incarnation");
        }
    }

    @Override
    public int compareTo(RingKey other) {
        int world = worldId.compareTo(other.worldId);
        if (world != 0) {
            return world;
        }
        int incarnation = Long.compare(worldIncarnation, other.worldIncarnation);
        return incarnation != 0 ? incarnation : domainId.compareTo(other.domainId);
    }
}
