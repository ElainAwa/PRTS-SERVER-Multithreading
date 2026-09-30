/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/**
 * The result of one commit step.
 *
 * <p>A commit either advances the sequence number or is refused with a code and its diagnostic
 * five. Refusing leaves the queue untouched, so the next attempt sees the same head and the order
 * cannot be repaired by a later commit.</p>
 *
 * @param committed whether the step committed the head
 * @param commitSeq sequence number after the step
 * @param code      refusal code, or {@code null} on a committed step
 * @param diag      diagnostic five, or {@code null} on a committed step
 */
public record CommitOrder(boolean committed, long commitSeq, RejectCode code, Diag5 diag) {

    /** @return a committed step */
    public static CommitOrder committed(long commitSeq) {
        return new CommitOrder(true, commitSeq, null, null);
    }

    /** @return a refused step */
    public static CommitOrder refused(RejectCode code, Diag5 diag) {
        return new CommitOrder(false, 0L, code, diag);
    }
}
