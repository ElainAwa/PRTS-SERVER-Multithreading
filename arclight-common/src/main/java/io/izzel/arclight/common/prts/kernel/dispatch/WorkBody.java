/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan.WorkBatch;

/** The computation a worker runs for one batch; the body sees only the frozen batch, its scratch and the token. */
@FunctionalInterface
public interface WorkBody {

    long run(WorkBatch batch, ArenaScratch target, CancelToken token) throws RetryableFault,
        NonRetryableFault, CancelledFault;

    /** A fault the batch may be redone from after the snapshot moved on. */
    class RetryableFault extends Exception {

        private static final long serialVersionUID = 1L;

        public RetryableFault(String message) {
            super(message);
        }
    }

    /** A fault that ends the worker attempt and leaves the batch to the tick thread. */
    class NonRetryableFault extends Exception {

        private static final long serialVersionUID = 1L;

        public NonRetryableFault(String message) {
            super(message);
        }
    }

    /** The signal a body leaves through when its token was cancelled. */
    class CancelledFault extends Exception {

        private static final long serialVersionUID = 1L;

        public CancelledFault() {
            super("the batch was cancelled at its checkpoint");
        }
    }
}
