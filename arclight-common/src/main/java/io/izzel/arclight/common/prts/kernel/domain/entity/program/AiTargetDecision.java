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
 * The target decision contract of one mob: a target change is decided before the owner may consume
 * it, the default admission keeps the whole host path, and an owner-synced admission refuses before
 * any effect because the owner-local target consumption is not implemented in this build. The
 * contract never claims a target was written: it only says which path may write one.
 */
public final class AiTargetDecision {

    /** The policy entry of this decision, beside the native hook it belongs to. */
    public static final String ENTRY = "native-hook/target";

    private static final DecisionNode NODE = DecisionNode.of(DecisionKind.TARGET, ENTRY, 0L, 0);

    /** What one target decision allowed; only the host path may change a target. */
    public record Consumption(Outcome outcome) {

        public Consumption {
            Objects.requireNonNull(outcome, "outcome");
        }

        /** The host path keeps the whole decision and may change the target as it always did. */
        public boolean hostPath() {
            return outcome.status() == Outcome.Status.PASSTHROUGH;
        }

        /** Whether the decision belongs to an owner-synced path instead of the host path. */
        public boolean owned() {
            return outcome.owned();
        }

        /** The owner refused before any effect, so no target write may happen. */
        public boolean refusedBeforeEffect() {
            return outcome.status() == Outcome.Status.REFUSED_BEFORE_EFFECT;
        }

        /** Whether the target path actually applied its decision and wrote the target. */
        public boolean applied() {
            return outcome.status() == Outcome.Status.APPLIED;
        }
    }

    private AiTargetDecision() {
    }

    /** The default admission of the build: the host path keeps the decision. */
    public static Consumption legacy() {
        return decide(EventAdmission.passthrough(), ExceptionPolicy.defaults());
    }

    /** One target decision under the given admission table and entry policy. */
    public static Consumption decide(EventAdmission admission, ExceptionPolicy policy) {
        Outcome outcome = NODE.run(admission, List.of(), null, policy,
            context -> Outcome.refusedBeforeEffect(DecisionKind.TARGET,
                "owner-target-consumption-not-implemented"));
        return new Consumption(outcome);
    }

    /**
     * Runs one host target consumption only when the decision kept the host path; an owner-synced
     * admission refuses first and the consumption never runs. The write is the caller's own host
     * code, so nothing here writes a world object by itself.
     */
    public static Consumption consume(EventAdmission admission, ExceptionPolicy policy,
                                      Runnable hostTargetWrite) {
        Objects.requireNonNull(hostTargetWrite, "hostTargetWrite");
        Consumption consumption = decide(admission, policy);
        if (consumption.hostPath()) {
            hostTargetWrite.run();
        }
        return consumption;
    }

    /**
     * The owner-local consumption of one target decision: the owner-synced admission admits the
     * node and the declared target write runs exactly once inside it, so the decision is applied
     * once and no second path writes the target.
     */
    public static Consumption consumeOwned(ExceptionPolicy policy, Runnable targetWrite) {
        Objects.requireNonNull(targetWrite, "targetWrite");
        Outcome outcome = NODE.run(EventAdmission.ownerSync(), List.of(), null, policy,
            context -> {
                targetWrite.run();
                return Outcome.applied(DecisionKind.TARGET, 1, "target-consumed");
            });
        return new Consumption(outcome);
    }
}
