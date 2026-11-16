/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.decision.DecisionKind;
import io.izzel.arclight.common.prts.kernel.decision.DecisionNode;
import io.izzel.arclight.common.prts.kernel.decision.EventAdmission;
import io.izzel.arclight.common.prts.kernel.decision.ExceptionPolicy;
import io.izzel.arclight.common.prts.kernel.decision.Outcome;

import java.util.List;
import java.util.Objects;

/**
 * The admission placeholder of the point-of-interest data operations: a take or a release is
 * admitted as one whole operation before it may run, the default admission keeps the host path,
 * and an owner-synced admission refuses before any effect. This build does not pick a single winner
 * between competing claims and does not touch a live point of interest; the winner rule belongs to
 * the later cross-entity work.
 */
public final class PoiAdmission {

    /** The point-of-interest data operations this placeholder admits. */
    public enum Operation {
        TAKE,
        RELEASE
    }

    /** The policy entry of the point-of-interest operations. */
    public static final String ENTRY = "native-hook/poi";

    private static final DecisionNode TAKE = DecisionNode.of(DecisionKind.POI_TAKE, ENTRY, 0L, 0);
    private static final DecisionNode RELEASE = DecisionNode.of(DecisionKind.POI_RELEASE, ENTRY,
        0L, 0);

    /** What one operation admission allowed. */
    public record Verdict(Operation operation, Outcome outcome) {

        public Verdict {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(outcome, "outcome");
        }

        public boolean hostPath() {
            return outcome.status() == Outcome.Status.PASSTHROUGH;
        }

        public boolean refusedBeforeEffect() {
            return outcome.status() == Outcome.Status.REFUSED_BEFORE_EFFECT;
        }

        /** No single winner is decided here, so no operation is ever reported as applied. */
        public boolean singleWinner() {
            return false;
        }
    }

    private PoiAdmission() {
    }

    /** The default admission of the build: the host path keeps the operation. */
    public static Verdict legacy(Operation operation) {
        return admit(operation, EventAdmission.passthrough(), ExceptionPolicy.defaults());
    }

    /** One point-of-interest operation admission under the given table and entry policy. */
    public static Verdict admit(Operation operation, EventAdmission admission,
                                ExceptionPolicy policy) {
        Objects.requireNonNull(operation, "operation");
        DecisionNode node = operation == Operation.TAKE ? TAKE : RELEASE;
        DecisionKind kind = operation == Operation.TAKE ? DecisionKind.POI_TAKE
            : DecisionKind.POI_RELEASE;
        Outcome outcome = node.run(admission, List.of(), null, policy,
            context -> Outcome.refusedBeforeEffect(kind, "poi-single-winner-out-of-scope"));
        return new Verdict(operation, outcome);
    }
}
