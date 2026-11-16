/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.List;
import java.util.Objects;

/**
 * One owner-local decision node: the admission of the whole chain is asked before any prefix, the
 * registration epoch the node was built against is checked before the first step and again before
 * the result is consumed, child effects reenter only inside the parent's reserved bound, and a
 * thrown exception leaves through the entry's own policy. The node never waits for another thread,
 * never polls a future and never asks a getter for a value that is not there: its path is a
 * synchronous call on the thread that already owns the decision.
 */
public final class DecisionNode {

    /** The body of one decision; it runs on the calling thread and returns its outcome. */
    @FunctionalInterface
    public interface Body {

        Outcome decide(Context context) throws Throwable;
    }

    /** The state the body sees: the prefix it applied and the child effects it is allowed to enter. */
    public static final class Context {

        private final BoundedReentry.Scope scope;
        private int applied;

        private Context(BoundedReentry.Scope scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        /** Records a prefix the body applied; a fault keeps exactly this prefix. */
        public void noteApplied(int units) {
            if (units < 0) {
                throw new IllegalArgumentException("an applied prefix cannot be negative");
            }
            applied += units;
        }

        public int applied() {
            return applied;
        }

        /** The most child effects this node may run. */
        public int reentryBound() {
            return scope.bound();
        }

        /** Enters one child effect under its own identity; crossing the bound faults the node. */
        public void enter(String childId) {
            scope.enter(childId);
        }

        /** Leaves one child effect. */
        public void leave(String childId) {
            scope.leave(childId);
        }

        /** The child effects entered so far. */
        public int entered() {
            return scope.entered();
        }
    }

    private final DecisionKind kind;
    private final String entry;
    private final long registeredEpoch;
    private final int reentryBound;
    private long passthroughs;
    private long admitted;
    private long refusals;
    private long faults;

    private DecisionNode(DecisionKind kind, String entry, long registeredEpoch, int reentryBound) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.entry = Objects.requireNonNull(entry, "entry");
        if (registeredEpoch < 0L) {
            throw new IllegalArgumentException("a registration generation cannot be negative");
        }
        if (reentryBound < 0) {
            throw new IllegalArgumentException("a reentry bound cannot be negative");
        }
        this.registeredEpoch = registeredEpoch;
        this.reentryBound = reentryBound;
    }

    /** Builds one decision node of one entry, bound to the registration generation it read. */
    public static DecisionNode of(DecisionKind kind, String entry, long registeredEpoch,
                                  int reentryBound) {
        return new DecisionNode(kind, entry, registeredEpoch, reentryBound);
    }

    /**
     * Runs one decision. A chain that is not admitted owner-synced is passed through as a whole and
     * the body is not run; a stale registration before the prefix refuses the node with no effect;
     * an exception leaves through the entry policy; a registration that moves after the prefix kept
     * the node's fault with that prefix and is never re-dispatched.
     */
    public Outcome run(EventAdmission admission, List<Class<?>> listeners, RegistrationEpoch epochs,
                       ExceptionPolicy policy, Body body) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(body, "body");
        EventAdmission.Verdict verdict = admission == null
            ? new EventAdmission.Verdict(EventAdmission.Mode.LEGACY, "no-admission-table", 0, 0)
            : admission.admit(listeners);
        if (!verdict.ownerSync()) {
            passthroughs++;
            return Outcome.passthrough(kind, "legacy-whole-event:" + verdict.reason());
        }
        if (epochs != null && epochs.admits(registeredEpoch) == RegistrationEpoch.Verdict.MISMATCH) {
            refusals++;
            return Outcome.refusedBeforeEffect(kind, "epoch-mismatch-before-prefix");
        }
        BoundedReentry.Scope scope = BoundedReentry.reserve(reentryBound).open();
        Context context = new Context(scope);
        Outcome outcome;
        try {
            outcome = body.decide(context);
        } catch (BoundedReentry.OverBound over) {
            faults++;
            return Outcome.faultKeepPrefix(kind, context.applied(), "reentry-over-bound");
        } catch (Throwable thrown) {
            switch (policy.decide(entry, thrown)) {
                case RECORD_AND_CONTINUE:
                    faults++;
                    return Outcome.faultRecorded(kind, context.applied(), "recorded:" + thrown);
                case FAULT_KEEP_PREFIX:
                    faults++;
                    return Outcome.faultKeepPrefix(kind, context.applied(), "fault:" + thrown);
                case PROPAGATE_TO_CALLER:
                default:
                    faults++;
                    if (thrown instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (thrown instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException("decision entry threw a checked exception", thrown);
            }
        } finally {
            scope.close();
        }
        if (outcome == null) {
            outcome = Outcome.applied(kind, context.applied(), "body-returned-none");
        }
        if (epochs != null && epochs.admits(registeredEpoch) == RegistrationEpoch.Verdict.MISMATCH) {
            // The nodes of the older registration may have applied a prefix; the newer one does not
            // re-dispatch it. The fault keeps the prefix and names the boundary.
            faults++;
            return Outcome.faultKeepPrefix(kind, context.applied(), "epoch-mismatch-mid-update");
        }
        admitted++;
        return outcome;
    }

    public DecisionKind kind() {
        return kind;
    }

    public String entry() {
        return entry;
    }

    public long registeredEpoch() {
        return registeredEpoch;
    }

    public int reentryBound() {
        return reentryBound;
    }

    public long passthroughs() {
        return passthroughs;
    }

    public long admitted() {
        return admitted;
    }

    public long refusals() {
        return refusals;
    }

    public long faults() {
        return faults;
    }
}
