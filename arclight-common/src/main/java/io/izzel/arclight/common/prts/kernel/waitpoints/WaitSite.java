/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * One real call site where a wait can happen, with everything a reader needs about it.
 *
 * <p>A wait point row answers "what kind of wait is this"; a site answers "where does it happen and
 * what does it belong to". The four elements are carried per site as well, so a site whose wait
 * needs a different producer or a different fallback can say so without splitting the class row,
 * and a site that is missing one of them is refused instead of being stored half described.</p>
 *
 * @param siteId        identity of this call site
 * @param wpId          wait point row that covers it
 * @param classRef      class the call site lives in
 * @param methodRef     method the call site lives in
 * @param tickPhase     part of the tick the call site runs in
 * @param shareClass    time-budget row the work of this site belongs to
 * @param producer      who produces the thing that is waited for
 * @param signal        how progress of that producer is observed
 * @param timeoutAction what happens when the wait runs out of time
 * @param degradeTo     what the wait degrades to when it cannot proceed
 * @param evidence      where the call site was recorded from
 * @param callSites     call sites this row stands for
 */
public record WaitSite(String siteId, String wpId, String classRef, String methodRef, String tickPhase,
                       String shareClass, String producer, Dec19Elements.ProgressSignal signal,
                       String timeoutAction, String degradeTo, String evidence, int callSites) {

    /** @return the element this site is missing, or {@code null} when it carries all four */
    public String missingElement() {
        return Dec19Elements.missingElement(producer, signal, timeoutAction, degradeTo);
    }

    /** @return {@code true} when every one of the four elements is present */
    public boolean complete() {
        return missingElement() == null;
    }
}
