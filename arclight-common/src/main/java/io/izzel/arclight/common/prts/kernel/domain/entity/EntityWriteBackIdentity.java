/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.Objects;

/**
 * The identity one row carries from the freeze to the write-back: the row identity (world
 * incarnation, UUID pair, numeric id as a hint, update ordinal) plus the lifecycle generation and
 * state revision the row was frozen against. A write is refused before it lands when the live
 * object no longer matches them.
 */
public record EntityWriteBackIdentity(EntityRowIdentity identity, long objectGeneration,
                                      long expectedStateRevision, long logicalUpdateOrdinal) {

    public EntityWriteBackIdentity {
        Objects.requireNonNull(identity, "identity");
        if (objectGeneration < 0L) {
            throw new IllegalArgumentException("a lifecycle generation cannot be negative");
        }
        if (expectedStateRevision < 0L) {
            throw new IllegalArgumentException("a state revision witness cannot be negative");
        }
        if (logicalUpdateOrdinal < 0L) {
            throw new IllegalArgumentException("an update ordinal cannot be negative");
        }
    }

    /** The object lifecycle generation the row was frozen against; zero when none was declared. */
    public boolean generationDeclared() {
        return objectGeneration > 0L;
    }

    /** The state revision the row was frozen against; zero when none was declared. */
    public boolean revisionDeclared() {
        return expectedStateRevision > 0L;
    }

    /** Whether any witness beside the identity was declared. */
    public boolean witnessed() {
        return generationDeclared() || revisionDeclared();
    }
}
