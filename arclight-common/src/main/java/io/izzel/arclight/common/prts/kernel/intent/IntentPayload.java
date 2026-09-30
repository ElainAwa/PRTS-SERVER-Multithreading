/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/**
 * Applies the write an intent stands for, at the moment the commit segment reaches it.
 *
 * <p>The commit segment runs on the thread that drives the tick and walks the order frozen at
 * planning time; this is the one place a deferred write reaches the world. An implementation
 * answers with a code when the write did not land, so a failure refuses the commit instead of
 * leaving the queue thinking the write happened.</p>
 */
public interface IntentPayload {

    /**
     * Applies one intent.
     *
     * @param intent the intent the commit segment reached
     * @return {@code null} when the write landed, otherwise the code the commit is refused with
     */
    RejectCode apply(WriteIntent intent);
}
