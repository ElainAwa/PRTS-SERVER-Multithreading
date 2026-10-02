/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;

/**
 * The computation a worker runs for one batch.
 *
 * <p>The body receives only the frozen batch, the scratch it may write and the cancellation token.
 * It never receives a world object, which is what keeps a worker unable to touch the world even by
 * accident. Its faults are classified: a retryable fault leaves the batch to the tick thread, a
 * non-retryable one does the same but is counted differently, and anything else is a hard fault
 * that retires the worker.</p>
 */
@FunctionalInterface
public interface WorkBody {

    /**
     * Runs one batch.
     *
     * @param batch  the frozen batch
     * @param target the scratch the result is written into
     * @param token  the cooperative cancellation token
     * @return the checksum of the values written, for the outcome
     * @throws RetryableFault    when the batch may be redone from a newer snapshot
     * @throws NonRetryableFault when the batch must be redone on the tick thread
     * @throws CancelledFault    when the token was observed cancelled
     */
    long run(WorkBatch batch, ArenaScratch target, CancelToken token) throws RetryableFault,
        NonRetryableFault, CancelledFault;

    /** A fault the batch may be redone from after the snapshot moved on. */
    class RetryableFault extends Exception {

        private static final long serialVersionUID = 1L;

        /** @param message what made the batch retryable */
        public RetryableFault(String message) {
            super(message);
        }
    }

    /** A fault that ends the worker attempt and leaves the batch to the tick thread. */
    class NonRetryableFault extends Exception {

        private static final long serialVersionUID = 1L;

        /** @param message what made the batch non-retryable */
        public NonRetryableFault(String message) {
            super(message);
        }
    }

    /** The signal a body leaves through when its token was cancelled. */
    class CancelledFault extends Exception {

        private static final long serialVersionUID = 1L;

        /** Creates the signal. */
        public CancelledFault() {
            super("the batch was cancelled at its checkpoint");
        }
    }
}
