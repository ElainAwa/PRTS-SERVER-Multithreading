/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

/**
 * The two entries the reserved pool may pay for.
 *
 * <p>The pool is a single column, deliberately outside the class shares. Only a forced
 * materialization or a migration wait may draw from it; anything else that did is a discipline
 * violation and is counted as one.</p>
 */
public enum ReservePurpose {

    /** Forced materialization of a dependency the main thread would otherwise wait for. */
    FORCED_MATERIALIZE,
    /** Waiting for a cross-domain or cross-world migration to finish. */
    MIGRATION_WAIT
}
