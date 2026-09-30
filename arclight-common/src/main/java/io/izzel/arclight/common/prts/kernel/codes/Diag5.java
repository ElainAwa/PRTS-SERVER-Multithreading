/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/**
 * The five pieces of diagnostic information every refusal carries.
 *
 * <p>A refusal without them is not readable from a log after the fact, and the code alone does not
 * say who hit it where. The record is built next to the counter, so both come from the same
 * place.</p>
 *
 * @param code      refusal code text
 * @param siteId    site that raised it
 * @param threadRef readable identity of the thread
 * @param worldId   world it belongs to
 * @param tickIndex tick it happened at
 */
public record Diag5(String code, String siteId, String threadRef, String worldId, long tickIndex) {

    /** @return one line a log can carry */
    public String line() {
        return "code=" + code + " site=" + siteId + " thread=" + threadRef
            + " world=" + worldId + " tick=" + tickIndex;
    }
}
