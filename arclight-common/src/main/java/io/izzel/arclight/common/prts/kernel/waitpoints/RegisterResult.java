/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * The result of offering a wait point for registration.
 *
 * <p>Three outcomes, and no silent fourth: the row was stored, the identity is already taken, or one
 * of the four elements is missing and is named. A refused declaration is never stored, so the
 * coverage report can trust every row it counts.</p>
 */
public sealed interface RegisterResult {

    /**
     * The row was stored.
     *
     * @param entry the stored row
     */
    record Ok(WaitPointEntry entry) implements RegisterResult {
    }

    /**
     * The identity is already registered; rows are never replaced silently.
     *
     * @param wpId the identity that was already taken
     */
    record DuplicateWpId(String wpId) implements RegisterResult {
    }

    /**
     * One of the four elements is missing.
     *
     * @param wpId  the identity that was offered
     * @param which the element that is missing
     */
    record MissingElement(String wpId, String which) implements RegisterResult {
    }
}
