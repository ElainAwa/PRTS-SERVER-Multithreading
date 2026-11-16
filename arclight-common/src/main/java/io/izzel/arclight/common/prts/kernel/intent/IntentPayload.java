/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** The one place a deferred write reaches the world, walked in the order frozen at planning time. */
public interface IntentPayload {

    /** How one attempt ended. A refusal that is final and happened before the action was entered is
     * safe to release; a fault says the action was entered and its effect cannot be known, so its
     * handle has to stay with the intent instead of being released silently. */
    record Outcome(RejectCode code, boolean finalRefusal, boolean effectUnknown) {

        public static final Outcome APPLIED = new Outcome(null, true, false);

        public Outcome(RejectCode code, boolean finalRefusal) {
            this(code, finalRefusal, false);
        }

        public static Outcome retryable(RejectCode code) {
            return new Outcome(code, false, false);
        }

        /** A final refusal that proves the action was never entered, so a release loses no effect. */
        public static Outcome rejected(RejectCode code) {
            return new Outcome(code, true, false);
        }

        /** A final refusal after the action was entered: unknown whether it landed, never retried. */
        public static Outcome fault(RejectCode code) {
            return new Outcome(code, true, true);
        }

        public boolean applied() {
            return code == null;
        }

        /** Whether the intent ended before its action, which is the only case a release may drop. */
        public boolean cancelledBeforeEffect() {
            return code != null && finalRefusal && !effectUnknown;
        }
    }

    Outcome apply(WriteIntent intent);

    /** The default does nothing, which is right for a payload that holds no state of its own. */
    default void release(WriteIntent intent) {
    }
}
