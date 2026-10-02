/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/**
 * Applies the write an intent stands for, at the moment the commit segment reaches it.
 *
 * <p>The commit segment runs on the thread that drives the tick and walks the order frozen at
 * planning time; this is the one place a deferred write reaches the world. An implementation answers
 * with an {@link Outcome}: either the write landed, or it did not and the refusal says whether
 * another attempt is worth making. A refusal the payload marks final releases the queue head, so a
 * payload that can never land cannot hold the channel behind it; anything else is retried only as
 * often as the retry budget of the channel allows.</p>
 */
public interface IntentPayload {

    /**
     * What one application of a payload did.
     *
     * @param code         the refusal code, or {@code null} when the write landed
     * @param finalRefusal whether the refusal is final, so the channel releases the intent instead
     *                     of offering it again
     */
    record Outcome(RejectCode code, boolean finalRefusal) {

        /** The outcome of a write that landed. */
        public static final Outcome APPLIED = new Outcome(null, true);

        /**
         * @param code the refusal code
         * @return a refusal the channel may offer again until the retry budget is spent
         */
        public static Outcome retryable(RejectCode code) {
            return new Outcome(code, false);
        }

        /**
         * @param code the refusal code
         * @return a refusal no later attempt can turn into a landing
         */
        public static Outcome rejected(RejectCode code) {
            return new Outcome(code, true);
        }

        /** @return whether the write landed */
        public boolean applied() {
            return code == null;
        }
    }

    /**
     * Applies one intent.
     *
     * @param intent the intent the commit segment reached
     * @return what the application did; never {@code null}
     */
    Outcome apply(WriteIntent intent);

    /**
     * Forgets the payload of an intent the channel released without applying it.
     *
     * <p>The default does nothing, which is right for a payload that holds no state of its own. A
     * store that keeps deferred writes pairs this with {@link #apply(WriteIntent)} so a released
     * intent cannot leave its write waiting forever.</p>
     *
     * @param intent the intent that was released
     */
    default void release(WriteIntent intent) {
    }
}
