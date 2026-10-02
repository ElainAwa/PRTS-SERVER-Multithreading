/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cooperative cancellation flag of one batch.
 *
 * <p>A thread cannot be stopped safely, so cancellation is cooperative: the token is set by the
 * merge when the deadline passes, and the body checks it at its bounded checkpoints. A body that
 * observes the flag leaves at the next checkpoint, which means a cancelled batch can run at most
 * one checkpoint longer than the deadline allows.</p>
 *
 * <p>The token carries the batch epoch it belongs to. A result written under an older epoch is
 * refused by the merge and counted, never silently dropped.</p>
 */
public final class CancelToken {

    private final long batchEpoch;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** @param batchEpoch the dispatch epoch the token belongs to */
    public CancelToken(long batchEpoch) {
        this.batchEpoch = batchEpoch;
    }

    /** @return the dispatch epoch of the batch */
    public long batchEpoch() {
        return batchEpoch;
    }

    /** @return whether cancellation was requested */
    public boolean cancelled() {
        return cancelled.get();
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} when this call was the one that set the flag
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
