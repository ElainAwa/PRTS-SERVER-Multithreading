/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The stable read source of one frozen plan: every row is captured once on the owner thread as a
 * plain snapshot and the worker only indexes that immutable array. A worker never asks a world or a
 * level for an entity, so no non-thread-safe map is touched from a worker; a row that was not
 * captured, or whose object is gone, is refused as a miss and never answered with a made-up row.
 */
public final class StableReadSource {

    /** One row captured at freeze time: what the owner read and what it saw. */
    public record Capture(EntityRowIdentity identity, long objectGeneration, long revision,
                          LiveRowSnapshot snapshot, boolean present) {

        public Capture {
            Objects.requireNonNull(identity, "identity");
            if (objectGeneration < 0L || revision < 0L) {
                throw new IllegalArgumentException("a captured generation cannot be negative");
            }
            if (present && snapshot == null) {
                throw new IllegalArgumentException("a present capture needs its snapshot");
            }
        }

        /** Captures one row whose live object was read. */
        public static Capture of(EntityRowIdentity identity, long objectGeneration, long revision,
                                 LiveRowSnapshot snapshot) {
            return new Capture(identity, objectGeneration, revision,
                Objects.requireNonNull(snapshot, "snapshot"), true);
        }

        /** Captures one row whose live object was already gone at the freeze. */
        public static Capture absent(EntityRowIdentity identity) {
            return new Capture(identity, 0L, 0L, null, false);
        }
    }

    /** Why one read had no row; a miss is never answered with a zero row. */
    public enum Miss {
        NO_CAPTURE,
        ABSENT,
        IDENTITY_CHANGED,
        REVISION_CHANGED
    }

    /** What one indexed read answered: a snapshot or an honest miss. */
    public record Read(LiveRowSnapshot snapshot, Miss miss) {

        public Read {
            if ((snapshot == null) == (miss == null)) {
                throw new IllegalArgumentException("a read answers either a row or a miss");
            }
        }

        public boolean hit() {
            return snapshot != null;
        }
    }

    private final List<Capture> captures;

    private StableReadSource(List<Capture> captures) {
        this.captures = List.copyOf(captures);
    }

    /** Freezes one capture list into the immutable source the workers index. */
    public static StableReadSource capture(List<Capture> captures) {
        Objects.requireNonNull(captures, "captures");
        List<Capture> copy = new ArrayList<>(captures);
        return new StableReadSource(copy);
    }

    /** The number of captured rows. */
    public int size() {
        return captures.size();
    }

    /**
     * Reads one captured row by its index. The method touches no world, no level and no map that a
     * worker may race: an index without a capture is a miss, and a captured-absent row is a miss.
     */
    public Read read(int index) {
        if (index < 0 || index >= captures.size()) {
            return new Read(null, Miss.NO_CAPTURE);
        }
        Capture capture = captures.get(index);
        if (!capture.present()) {
            return new Read(null, Miss.ABSENT);
        }
        LiveRowSnapshot snapshot = capture.snapshot();
        if (!snapshot.identity().sameObject(capture.identity())) {
            return new Read(null, Miss.IDENTITY_CHANGED);
        }
        if (capture.revision() > 0L && snapshot.revision() != capture.revision()) {
            return new Read(null, Miss.REVISION_CHANGED);
        }
        return new Read(snapshot, null);
    }
}
