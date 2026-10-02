/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/** A wait point row answers "what kind of wait is this"; a site answers "where does it happen and
 * what does it belong to". */
public record WaitSite(String siteId, String wpId, String classRef, String methodRef, String tickPhase,
                       String shareClass, String producer, Dec19Elements.ProgressSignal signal,
                       String timeoutAction, String degradeTo, String evidence, int callSites) {

    public String missingElement() {
        return Dec19Elements.missingElement(producer, signal, timeoutAction, degradeTo);
    }

    public boolean complete() {
        return missingElement() == null;
    }
}
