/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** The commit segment runs on the thread that drives the tick and walks the order frozen at
 * planning time; this is the one place a deferred write reaches the world. */
public interface IntentPayload {

    /** What one application of a payload did. */
    record Outcome(RejectCode code, boolean finalRefusal) {

        /** The outcome of a write that landed. */
        public static final Outcome APPLIED = new Outcome(null, true);

        public static Outcome retryable(RejectCode code) {
            return new Outcome(code, false);
        }

        public static Outcome rejected(RejectCode code) {
            return new Outcome(code, true);
        }

        public boolean applied() {
            return code == null;
        }
    }

    /** Applies one intent. */
    Outcome apply(WriteIntent intent);

    /** The default does nothing, which is right for a payload that holds no state of its own. */
    default void release(WriteIntent intent) {
    }
}
