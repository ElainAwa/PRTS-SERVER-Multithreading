/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The owner-local air feedback: the tick thread freezes the event payload and the S2a row fields,
 * the existing decision contracts run the one decision, and the host consumes the result before its
 * write. The property is off in every shipped build; off, the host legacy path is untouched. */
public final class AirOwnerLocalFeedback {

    /** The one internal property; not a configuration key and never enabled by a shipped file. */
    public static final String PROPERTY = "arclight.prts.airOwnerLocal";

    /** The frozen input: the event payload plus the row fields captured on the tick thread. */
    public record Input(int requested, int currentAir, boolean eventCancelled, int eventAmount,
                        String capability, Set<String> boundGroups, long registrationEpoch) {

        public Input {
            Objects.requireNonNull(capability, "capability");
            if (requested < 0 || eventAmount < 0) {
                throw new IllegalArgumentException("air amounts cannot be negative");
            }
        }
    }

    /** The result the host consumes before its write; ownerConsumed=false keeps the legacy path. */
    public record Result(boolean ownerConsumed, boolean cancelled, int amount, String reason,
                         String disposition) {
    }

    private AirOwnerLocalFeedback() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Runs the owner-local decision with the existing contracts; unknown capability stays COMPAT. */
    public static Result decide(Input input) {
        Objects.requireNonNull(input, "input");
        if (!enabled()) {
            return new Result(false, false, -1, "property-off", "host-fallback");
        }
        if (!"air-change".equals(input.capability())) {
            return new Result(false, false, -1, "compat:unknown-capability:" + input.capability(),
                "compat-refuse");
        }
        if (input.boundGroups() == null || !input.boundGroups().contains("LIFECYCLE")) {
            return new Result(false, false, -1, "unbound-read:LIFECYCLE", "host-fallback");
        }
        EventAdmission admission = EventAdmission.ownerSync()
            .declare(AirHostDecision.Event.class, EventAdmission.Mode.OWNER_SYNC);
        DecisionNode node = DecisionNode.of(DecisionKind.AIR_CHANGE,
            ExceptionPolicy.NATIVE_HOOK_AIR, Math.max(0L, input.registrationEpoch()), 0);
        Outcome outcome = node.run(admission, List.of(AirHostDecision.Event.class), null,
            ExceptionPolicy.defaults(), context -> {
                if (input.eventCancelled()) {
                    return Outcome.cancelled(DecisionKind.AIR_CHANGE, "owner-cancel");
                }
                if (input.eventAmount() != input.requested()) {
                    return Outcome.modified(DecisionKind.AIR_CHANGE, "owner-modified");
                }
                return Outcome.applied(DecisionKind.AIR_CHANGE, 1, "owner-applied");
            });
        return switch (outcome.status()) {
            case CANCELLED -> new Result(true, true, input.currentAir(), "owner-cancel",
                "owner-cancel");
            case MODIFIED, APPLIED -> new Result(true, false, input.eventAmount(),
                outcome.detail(), "owner-write");
            case REFUSED_BEFORE_EFFECT -> new Result(false, false, -1,
                outcome.detail(), "host-fallback");
            case FAULT_KEEP_PREFIX, FAULT_RECORDED -> new Result(false, false, -1,
                "fault:" + outcome.detail(), "host-fallback");
            default -> new Result(false, false, -1, "passthrough:" + outcome.detail(),
                "host-fallback");
        };
    }
}
