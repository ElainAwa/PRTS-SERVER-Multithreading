/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/**
 * The result of one commit step.
 *
 * <p>A step either commits the head, or releases it with a final refusal, or refuses it and leaves
 * it where it is. A plain refusal leaves the queue untouched, so the next attempt sees the same head
 * and the order cannot be repaired by a later commit; a release consumes the position and counts the
 * refusal, which is how a payload that can never land stops holding the channel behind it.</p>
 *
 * @param committed whether the step committed the head
 * @param commitSeq sequence number after the step
 * @param code      refusal code, or {@code null} on a committed step
 * @param diag      diagnostic five, or {@code null} on a committed step
 * @param released  whether the head was released instead of being applied
 */
public record CommitOrder(boolean committed, long commitSeq, RejectCode code, Diag5 diag,
                          boolean released) {

    /** @return a committed step */
    public static CommitOrder committed(long commitSeq) {
        return new CommitOrder(true, commitSeq, null, null, false);
    }

    /** @return a refused step that leaves the head where it is */
    public static CommitOrder refused(RejectCode code, Diag5 diag) {
        return new CommitOrder(false, 0L, code, diag, false);
    }

    /** @return a refused step that consumed the head */
    public static CommitOrder released(RejectCode code, Diag5 diag) {
        return new CommitOrder(false, 0L, code, diag, true);
    }
}
