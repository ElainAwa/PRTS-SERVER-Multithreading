/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.List;

/**
 * The host-side air decision glue: the plugin air-change event is dispatched by the host, and the
 * decision contract decides which path may consume its result. The default admission is the legacy
 * one, so the host event is dispatched exactly once and the air supply is written from the event's
 * amount exactly as before; a caller that holds a frozen owner-local input may hand it over and the
 * result is consumed before the host dispatch. The glue never waits for another thread.
 */
public final class AirHostDecision {

    /** The event result the glue consumes; the host supplies the real plugin event behind it. */
    public interface Event {

        boolean cancelled();

        int amount();
    }

    /** The host row the glue reads and writes: it is the real entity in production. */
    public interface Host {

        boolean valid();

        int currentAirSupply();

        Event dispatch(int requested);

        void writeAir(int amount);

        void markDirty();
    }

    /** What the host call should do next: cancel the vanilla write, or write the given amount. */
    public record Result(boolean cancelled, int writtenAir) {
    }

    private static final DecisionNode NODE = DecisionNode.of(DecisionKind.AIR_CHANGE,
        ExceptionPolicy.NATIVE_HOOK_AIR, 0L, 0);
    private static final EventAdmission LEGACY_ADMISSION = EventAdmission.passthrough();
    private static final ExceptionPolicy DEFAULT_POLICY = ExceptionPolicy.defaults();

    private AirHostDecision() {
    }

    /** The legacy path: the default admission of the build. */
    public static Result decide(Host host, int requested) {
        return decide(LEGACY_ADMISSION, DEFAULT_POLICY, host, requested);
    }

    /** Host consumption of one frozen owner-local input; the caller passes the captured fields. */
    public static Result decide(AirOwnerLocalFeedback.Input input, Host host, int requested) {
        if (input != null && AirOwnerLocalFeedback.enabled()) {
            AirOwnerLocalFeedback.Result owner = AirOwnerLocalFeedback.decide(input);
            if (owner.ownerConsumed()) {
                if (owner.cancelled()) {
                    host.markDirty();
                    return new Result(true, host.currentAirSupply());
                }
                host.writeAir(owner.amount());
                return new Result(false, owner.amount());
            }
        }
        return decide(LEGACY_ADMISSION, DEFAULT_POLICY, host, requested);
    }

    /** One air decision under the given admission and entry policy. */
    public static Result decide(EventAdmission admission, ExceptionPolicy policy, Host host,
                                int requested) {
        Outcome gate = NODE.run(admission, List.of(), null, policy,
            context -> Outcome.refusedBeforeEffect(DecisionKind.AIR_CHANGE,
                "owner-local-air-not-implemented"));
        if (gate.owned()) {
            // Nothing of the host event may run before its result exists; an owner-local air write
            // is not implemented in this slice, so the host write is cancelled with no effect.
            return new Result(true, host.currentAirSupply());
        }
        try {
            Event event = host.dispatch(requested);
            if (event.cancelled() && host.currentAirSupply() != -1) {
                host.markDirty();
                return new Result(true, host.currentAirSupply());
            }
            host.writeAir(event.amount());
            return new Result(false, event.amount());
        } catch (Throwable thrown) {
            switch (policy.decide(ExceptionPolicy.NATIVE_HOOK_AIR, thrown)) {
                case PROPAGATE_TO_CALLER:
                    if (thrown instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (thrown instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException("the air entry threw a checked exception", thrown);
                case RECORD_AND_CONTINUE, FAULT_KEEP_PREFIX:
                default:
                    return new Result(true, host.currentAirSupply());
            }
        }
    }
}
