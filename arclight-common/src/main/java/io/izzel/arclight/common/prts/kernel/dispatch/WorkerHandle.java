/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one-shot answer slot of a dispatched batch.
 *
 * <p>The worker writes the outcome, and the merge may write it first when the deadline passes. The
 * first writer wins, so a worker that finishes after the merge gave up cannot overwrite the
 * cancellation: it observes the completed handle and its result is counted as late instead.</p>
 */
public final class WorkerHandle {

    private final long batchId;
    private final long batchEpoch;
    private final CompletableFuture<TaskOutcome> outcome = new CompletableFuture<>();

    /**
     * Creates the handle of one batch.
     *
     * @param batchId    identity of the batch
     * @param batchEpoch the dispatch epoch of the batch
     */
    public WorkerHandle(long batchId, long batchEpoch) {
        this.batchId = batchId;
        this.batchEpoch = batchEpoch;
    }

    /** @return identity of the batch */
    public long batchId() {
        return batchId;
    }

    /** @return the dispatch epoch of the batch */
    public long batchEpoch() {
        return batchEpoch;
    }

    /**
     * Publishes an outcome if none was published yet.
     *
     * @param result the outcome to publish
     * @return {@code true} when this call published it
     */
    public boolean complete(TaskOutcome result) {
        return outcome.complete(result);
    }

    /** @return whether an outcome was published */
    public boolean isDone() {
        return outcome.isDone();
    }

    /**
     * Waits for the outcome until the deadline.
     *
     * @param timeoutNanos how long to wait, in nanoseconds
     * @return the outcome, or {@code null} when the deadline passed first
     */
    public TaskOutcome await(long timeoutNanos) {
        if (outcome.isDone()) {
            return outcome.getNow(null);
        }
        try {
            return outcome.get(Math.max(0L, timeoutNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** @return the published outcome, or {@code null} when there is none yet */
    public TaskOutcome peek() {
        return outcome.getNow(null);
    }
}
