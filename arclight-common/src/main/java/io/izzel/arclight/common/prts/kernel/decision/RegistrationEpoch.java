/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.Objects;

/** The registration epoch of the decision entries: a node is bound to the generation it read, and a
 * generation published mid-update faults it with the prefix it already applied. */
public final class RegistrationEpoch {

    /** What one check found. */
    public enum Verdict {
        /** The epoch the node was built against is still current. */
        MATCH,
        /** Another registration generation was published since the node was built. */
        MISMATCH
    }

    private long current;
    private long publishes;

    /** Publishes a new registration generation at the caller's safe point. */
    public synchronized long publish() {
        current++;
        publishes++;
        return current;
    }

    /** The generation live right now. */
    public synchronized long current() {
        return current;
    }

    /** How many generations were published; an observation of the table itself, not a read key. */
    public synchronized long publishes() {
        return publishes;
    }

    /** Whether a node built against one generation may still run. */
    public synchronized Verdict admits(long boundGeneration) {
        if (boundGeneration < 0L) {
            throw new IllegalArgumentException("a bound registration generation cannot be negative");
        }
        return boundGeneration == current ? Verdict.MATCH : Verdict.MISMATCH;
    }

    /** The state a node freezes before it starts: the live generation it is bound to. */
    public synchronized long bind() {
        return current;
    }

    /** A node bound to this generation, kept apart from the table type. */
    public record Bound(long generation) {

        public Bound {
            if (generation < 0L) {
                throw new IllegalArgumentException("a bound registration generation cannot be negative");
            }
        }

        public static Bound of(RegistrationEpoch epochs) {
            Objects.requireNonNull(epochs, "epochs");
            return new Bound(epochs.bind());
        }
    }
}
