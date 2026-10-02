/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.concurrent.atomic.AtomicBoolean;

/** The cooperative cancellation flag of one batch. A thread cannot be stopped safely, so
 * cancellation is cooperative: the token is set by the merge when the deadline passes, and the
 * body checks it at its bounded checkpoints. */
public final class CancelToken {

    private final long batchEpoch;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public CancelToken(long batchEpoch) {
        this.batchEpoch = batchEpoch;
    }

    public long batchEpoch() {
        return batchEpoch;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    /** Requests cancellation. */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }
}
