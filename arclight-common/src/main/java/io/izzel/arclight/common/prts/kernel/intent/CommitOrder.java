/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger.Diag5;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

/** The result of one commit step. A step either commits the head, or releases it with a final
 * refusal, or refuses it and leaves it where it is. */
public record CommitOrder(boolean committed, long commitSeq, RejectCode code, Diag5 diag,
                          boolean released) {

    public static CommitOrder committed(long commitSeq) {
        return new CommitOrder(true, commitSeq, null, null, false);
    }

    public static CommitOrder refused(RejectCode code, Diag5 diag) {
        return new CommitOrder(false, 0L, code, diag, false);
    }

    public static CommitOrder released(RejectCode code, Diag5 diag) {
        return new CommitOrder(false, 0L, code, diag, true);
    }
}
