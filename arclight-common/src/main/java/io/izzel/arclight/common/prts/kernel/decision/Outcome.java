/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.Objects;

/** What one decision node did: a passthrough leaves the whole call to the host, a fault keeps the
 * applied prefix and re-dispatches nothing, and no result claims a success the node did not reach. */
public record Outcome(Status status, DecisionKind kind, int appliedPrefix, String detail) {

    /** The terminal state of one decision node. */
    public enum Status {

        PASSTHROUGH,
        APPLIED,
        CANCELLED,
        MODIFIED,
        REFUSED_BEFORE_EFFECT,
        FAULT_KEEP_PREFIX,
        FAULT_RECORDED
    }

    public Outcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(kind, "kind");
        if (appliedPrefix < 0) {
            throw new IllegalArgumentException("an applied prefix cannot be negative");
        }
        detail = detail == null ? "" : detail;
    }

    public static Outcome passthrough(DecisionKind kind, String detail) {
        return new Outcome(Status.PASSTHROUGH, kind, 0, detail);
    }

    public static Outcome applied(DecisionKind kind, int units, String detail) {
        return new Outcome(Status.APPLIED, kind, units, detail);
    }

    public static Outcome cancelled(DecisionKind kind, String detail) {
        return new Outcome(Status.CANCELLED, kind, 0, detail);
    }

    public static Outcome modified(DecisionKind kind, String detail) {
        return new Outcome(Status.MODIFIED, kind, 0, detail);
    }

    public static Outcome refusedBeforeEffect(DecisionKind kind, String detail) {
        return new Outcome(Status.REFUSED_BEFORE_EFFECT, kind, 0, detail);
    }

    public static Outcome faultKeepPrefix(DecisionKind kind, int appliedPrefix, String detail) {
        return new Outcome(Status.FAULT_KEEP_PREFIX, kind, appliedPrefix, detail);
    }

    public static Outcome faultRecorded(DecisionKind kind, int appliedPrefix, String detail) {
        return new Outcome(Status.FAULT_RECORDED, kind, appliedPrefix, detail);
    }

    /** Whether the node took the decision at all. */
    public boolean owned() {
        return status != Status.PASSTHROUGH;
    }

    /** Whether a side effect of the node may have happened and is kept. */
    public boolean prefixKept() {
        return appliedPrefix > 0 && (status == Status.FAULT_KEEP_PREFIX
            || status == Status.FAULT_RECORDED);
    }
}
