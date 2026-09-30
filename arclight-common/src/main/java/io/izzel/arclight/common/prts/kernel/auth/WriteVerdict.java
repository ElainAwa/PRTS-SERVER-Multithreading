/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.ConflictClass;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

/**
 * What the decision point answers for one attempt.
 *
 * <p>Exactly one of the three dispositions is always present. A code is attached when the attempt
 * was refused or pushed through the controlled channel; a clean grant carries none. The diagnostic
 * five is not stored here: the ledger writes it next to the counter, so the record and the count
 * come from one place.</p>
 *
 * @param disposition      grant, intent or deny; never null
 * @param code             refusal code, or {@code null} on a clean grant
 * @param conflictClass    how the conflict is handled, or {@code null} on a clean grant
 * @param reason           one sentence a human can read in the trace
 * @param intentRef        identity of the intent record, zero when there is none
 * @param wouldDegradeLevel level the refusal would enter, none when it would not
 * @param retryBudget      retries left for this attempt; never negative
 */
public record WriteVerdict(WriteDisposition disposition, RejectCode code, ConflictClass conflictClass,
                           String reason, long intentRef, DegradeLevel wouldDegradeLevel,
                           int retryBudget) {

    public WriteVerdict {
        if (disposition == null) {
            throw new IllegalArgumentException("a verdict needs a disposition");
        }
        if (retryBudget < 0) {
            throw new IllegalArgumentException("the retry budget cannot be negative");
        }
        if (wouldDegradeLevel == null) {
            wouldDegradeLevel = DegradeLevel.NONE;
        }
    }

    /** @return a grant with no conflict attached */
    public static WriteVerdict grant(String reason) {
        return new WriteVerdict(WriteDisposition.GRANT, null, null, reason, 0L,
            DegradeLevel.NONE, 0);
    }

    /** @return a grant that carries the retry budget of the decision point */
    public static WriteVerdict grant(String reason, int retryBudget) {
        return new WriteVerdict(WriteDisposition.GRANT, null, null, reason, 0L,
            DegradeLevel.NONE, retryBudget);
    }

    /** @return a refusal with its code and conflict class */
    public static WriteVerdict deny(RejectCode code, ConflictClass conflictClass, String reason) {
        return new WriteVerdict(WriteDisposition.DENY, code, conflictClass, reason, 0L,
            DegradeLevel.NONE, 0);
    }

    /** @return a refusal that also names the level a degradation would enter */
    public static WriteVerdict deny(RejectCode code, ConflictClass conflictClass, String reason,
                                    DegradeLevel wouldDegradeLevel) {
        return new WriteVerdict(WriteDisposition.DENY, code, conflictClass, reason, 0L,
            wouldDegradeLevel, 0);
    }

    /** @return a verdict that hands the write to the intent channel */
    public static WriteVerdict intent(RejectCode code, String reason, long intentRef,
                                      int retryBudget) {
        return new WriteVerdict(WriteDisposition.INTENT, code, ConflictClass.QUEUE, reason,
            intentRef, DegradeLevel.NONE, retryBudget);
    }
}
