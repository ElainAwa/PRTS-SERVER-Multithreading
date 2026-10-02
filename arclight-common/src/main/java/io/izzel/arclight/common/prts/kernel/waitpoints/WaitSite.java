/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/** A wait point row says what kind of wait it is; a site says where it happens and what it belongs to. */
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
