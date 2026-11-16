/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.Objects;
import java.util.Set;

/**
 * One read permit of one row inside one read phase: the object it was frozen against, the lifecycle
 * generation and the state revision the reader expects, the field groups it may read, and the tick
 * window it is valid in. A permit says what it would read and for how long; it grants nothing by
 * itself, and a reader it was not issued to never reads through it.
 */
public record ReadPermit(EntityRowIdentity identity, long objectGeneration, long expectedRevision,
                         Set<FieldGroup> groups, long waveId, long issuedAt, long expiresAt) {

    /** The closed field groups one permit may name; a group outside it is no read right. */
    public enum FieldGroup {
        IDENTITY,
        POSITION,
        ROTATION,
        MOTION,
        AIR,
        HEALTH
    }

    public ReadPermit {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(groups, "groups");
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("a permit needs at least one field group");
        }
        groups = Set.copyOf(groups);
        if (objectGeneration < 0L || expectedRevision < 0L) {
            throw new IllegalArgumentException("a permit generation cannot be negative");
        }
        if (expiresAt < issuedAt) {
            throw new IllegalArgumentException("a permit cannot expire before it was issued");
        }
    }

    /** Whether this permit names one field group. */
    public boolean covers(FieldGroup group) {
        return groups.contains(group);
    }

    /** Whether the permit window is still open at one tick. */
    public boolean validAt(long tick) {
        return tick <= expiresAt;
    }
}
