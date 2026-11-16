/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The host-side damage decision glue: the damage entry is admitted before the host computes or
 * dispatches its event, and the result the caller consumes is the event's own cancellation and
 * damage. The default admission is the legacy one, so the host event is dispatched once and the
 * health is written from the event's damage exactly as before; an owner-local consumption is not
 * implemented and refuses before any effect. The glue never waits for another thread.
 */
public final class DamageHostDecision {

    /** The canceled/damage answer the host call consumes. */
    public record Consumed(boolean cancelled, double damage) {

        public Consumed {
            if (Double.isNaN(damage)) {
                throw new IllegalArgumentException("a damage answer cannot be NaN");
            }
        }
    }

    /** One admitted host dispatch: the decision outcome and the event the host dispatched, if any. */
    public record Gate<E>(Outcome outcome, E event) {
    }

    private static final DecisionNode NODE = DecisionNode.of(DecisionKind.DAMAGE,
        ExceptionPolicy.NATIVE_HOOK_DAMAGE, 0L, 0);
    private static final EventAdmission LEGACY_ADMISSION = EventAdmission.passthrough();
    private static final ExceptionPolicy DEFAULT_POLICY = ExceptionPolicy.defaults();

    private DamageHostDecision() {
    }

    /** The legacy consumption of one event: a missing or cancelled event cancels, else its damage. */
    public static Consumed consume(boolean present, boolean cancelled, double damage) {
        if (!present || cancelled) {
            return new Consumed(true, 0.0);
        }
        return new Consumed(false, damage);
    }

    /** The admission of one damage entry under the built-in legacy table and entry policy. */
    public static Outcome admit() {
        return admit(LEGACY_ADMISSION, DEFAULT_POLICY);
    }

    /** The admission of one damage entry under the given table and entry policy. */
    public static Outcome admit(EventAdmission admission, ExceptionPolicy policy) {
        return NODE.run(admission, List.of(), null, policy,
            context -> Outcome.refusedBeforeEffect(DecisionKind.DAMAGE,
                "owner-local-damage-not-implemented"));
    }

    /**
     * Admits the damage entry and runs the host dispatch only when the admission is the legacy one.
     * An owner-local dispatch is refused before any effect, and a dispatch exception leaves through
     * the entry's own policy: the native hook hands it back to its caller by default.
     */
    public static <E> Gate<E> dispatch(EventAdmission admission, ExceptionPolicy policy,
                                       Supplier<E> hostDispatch) {
        Objects.requireNonNull(hostDispatch, "hostDispatch");
        Outcome gate = admit(admission, policy);
        if (gate.owned()) {
            return new Gate<>(gate, null);
        }
        try {
            return new Gate<>(gate, hostDispatch.get());
        } catch (Throwable thrown) {
            switch (policy.decide(ExceptionPolicy.NATIVE_HOOK_DAMAGE, thrown)) {
                case PROPAGATE_TO_CALLER:
                    if (thrown instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (thrown instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException("the damage entry threw a checked exception", thrown);
                case RECORD_AND_CONTINUE:
                    return new Gate<>(Outcome.faultRecorded(DecisionKind.DAMAGE, 0,
                        "dispatch-recorded"), null);
                case FAULT_KEEP_PREFIX:
                default:
                    return new Gate<>(Outcome.faultKeepPrefix(DecisionKind.DAMAGE, 0,
                        "dispatch-fault"), null);
            }
        }
    }
}
