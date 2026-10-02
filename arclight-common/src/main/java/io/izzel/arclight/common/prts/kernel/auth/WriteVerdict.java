/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger.ConflictClass;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

/** What the decision point answers for one attempt. Exactly one of the three dispositions is
 * always present. */
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

    public static WriteVerdict grant(String reason) {
        return new WriteVerdict(WriteDisposition.GRANT, null, null, reason, 0L,
            DegradeLevel.NONE, 0);
    }

    public static WriteVerdict grant(String reason, int retryBudget) {
        return new WriteVerdict(WriteDisposition.GRANT, null, null, reason, 0L,
            DegradeLevel.NONE, retryBudget);
    }

    public static WriteVerdict deny(RejectCode code, ConflictClass conflictClass, String reason) {
        return new WriteVerdict(WriteDisposition.DENY, code, conflictClass, reason, 0L,
            DegradeLevel.NONE, 0);
    }

    public static WriteVerdict deny(RejectCode code, ConflictClass conflictClass, String reason,
                                    DegradeLevel wouldDegradeLevel) {
        return new WriteVerdict(WriteDisposition.DENY, code, conflictClass, reason, 0L,
            wouldDegradeLevel, 0);
    }

    public static WriteVerdict intent(RejectCode code, String reason, long intentRef,
                                      int retryBudget) {
        return new WriteVerdict(WriteDisposition.INTENT, code, ConflictClass.QUEUE, reason,
            intentRef, DegradeLevel.NONE, retryBudget);
    }
}
