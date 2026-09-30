/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * A wait point offered for registration.
 *
 * <p>The four elements - producer, progress signal, timeout action and degradation target - are
 * mandatory; the rest identifies the row and the call site it covers. Registration is the only
 * legal way for a wait point to appear in code, so this declaration is the entry to that list.</p>
 *
 * @param wpId          identity of this wait point
 * @param className     class of waits this row covers
 * @param producer      who produces the thing that is waited for
 * @param signal        how progress of that producer is observed
 * @param timeoutAction what happens when the wait runs out of time
 * @param degradeTo     what the wait degrades to when it cannot proceed
 * @param worldScope    scope the row applies to
 * @param callSiteRef   call site the row covers
 * @param revision      revision of this declaration
 */
public record WaitPointDeclaration(String wpId, String className, String producer,
                                   Dec19Elements.ProgressSignal signal, String timeoutAction,
                                   String degradeTo, String worldScope, String callSiteRef,
                                   int revision) {
}
