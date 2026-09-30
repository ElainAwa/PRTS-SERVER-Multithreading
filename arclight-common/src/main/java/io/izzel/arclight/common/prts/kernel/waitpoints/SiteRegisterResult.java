/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * The result of offering a call site for registration.
 *
 * <p>The same three outcomes a wait point row has, and the same rule: a site missing one of the
 * four elements is named and refused, never stored, so the coverage report can trust every row it
 * counts and can list what is still missing.</p>
 */
public sealed interface SiteRegisterResult {

    /**
     * The site was stored.
     *
     * @param site the stored site
     */
    record Ok(WaitSite site) implements SiteRegisterResult {
    }

    /**
     * The identity is already registered.
     *
     * @param siteId the identity that was already taken
     */
    record DuplicateSiteId(String siteId) implements SiteRegisterResult {
    }

    /**
     * One of the four elements is missing.
     *
     * @param siteId the identity that was offered
     * @param which  the element that is missing
     */
    record MissingElement(String siteId, String which) implements SiteRegisterResult {
    }
}
