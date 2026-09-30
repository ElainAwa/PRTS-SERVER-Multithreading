/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * One registered wait point.
 *
 * <p>A registered row is immutable: a new revision is a new registration, never an edit in place,
 * so a reader of the coverage report sees exactly which declaration produced a row.</p>
 *
 * @param wpId          identity of this wait point
 * @param className     class of waits this row covers
 * @param producer      who produces the thing that is waited for
 * @param signal        how progress of that producer is observed
 * @param timeoutAction what happens when the wait runs out of time
 * @param degradeTo     what the wait degrades to when it cannot proceed
 * @param worldScope    scope the row applies to
 * @param callSiteRef   call site the row covers
 * @param revision      revision of the declaration this row came from
 */
public record WaitPointEntry(String wpId, String className, String producer,
                             Dec19Elements.ProgressSignal signal, String timeoutAction,
                             String degradeTo, String worldScope, String callSiteRef,
                             int revision) {

    /** @return {@code true} when every one of the four elements is present */
    public boolean complete() {
        return Dec19Elements.missingElement(new WaitPointDeclaration(wpId, className, producer,
            signal, timeoutAction, degradeTo, worldScope, callSiteRef, revision)) == null;
    }
}
