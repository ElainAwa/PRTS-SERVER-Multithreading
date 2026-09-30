/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/**
 * The three dispositions a write attempt can leave with.
 *
 * <p>There is no fourth state: a skipped beat and a degraded write are both refusals, so the
 * accounting closure holds for every attempt.</p>
 */
public enum WriteDisposition {

    /** The holder owns the domain and the version matches; the write may proceed. */
    GRANT,
    /** The write is frozen into the intent channel and runs in the commit segment in order. */
    INTENT,
    /** The write is refused; a code and the diagnostic five are attached. */
    DENY
}
