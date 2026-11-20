/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** Internal carrier for one suspended owner update: the position in its declared segment and the
 * identity/witness/resources a later resume must still match. No production consumer yet; the
 * public resume state API and the demand sites are separate authorizations. */
final class OwnerWaitContinuation {

    record Identity(String worldId, long entityId, long generation) {

        Identity {
            if (worldId == null || worldId.isEmpty() || entityId < 0L || generation < 0L) {
                throw new IllegalArgumentException("owner wait identity is incomplete");
            }
        }
    }

    record Position(String segment, int programCounter, long logicalTick) {

        Position {
            if (segment == null || segment.isEmpty() || programCounter < 0 || logicalTick < 0L) {
                throw new IllegalArgumentException("owner wait position is incomplete");
            }
        }
    }

    record Held(Identity identity, long logicalTick, String witnessDigest, boolean leaseHeld,
                boolean permitHeld) {

        Held {
            if (identity == null) {
                throw new IllegalArgumentException("owner wait held identity is required");
            }
        }
    }

    private final Position position;
    private final Held held;
    private boolean resumed;

    private OwnerWaitContinuation(Position position, Held held) {
        this.position = position;
        this.held = held;
    }

    /** Freezes one continuation when the position and held state describe the same update. */
    static OwnerWaitContinuation suspend(Position position, Held held) {
        if (position == null || held == null || held.logicalTick() != position.logicalTick()) {
            return null;
        }
        if (!held.leaseHeld() || !held.permitHeld()) {
            return null;
        }
        if (held.witnessDigest() == null || held.witnessDigest().isEmpty()) {
            return null;
        }
        return new OwnerWaitContinuation(position, held);
    }

    /** Resumes only when the identity, tick, witness and both resources still match. */
    boolean resume(Held current) {
        if (resumed || current == null || !held.identity().equals(current.identity())) {
            return false;
        }
        if (held.logicalTick() != current.logicalTick()) {
            return false;
        }
        if (!held.witnessDigest().equals(current.witnessDigest())) {
            return false;
        }
        if (!current.leaseHeld() || !current.permitHeld()) {
            return false;
        }
        resumed = true;
        return true;
    }

    Position position() {
        return position;
    }

    Held held() {
        return held;
    }

    boolean resumed() {
        return resumed;
    }
}
