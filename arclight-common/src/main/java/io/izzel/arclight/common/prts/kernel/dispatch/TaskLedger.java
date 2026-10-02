/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one-way state of every batch, and the closure of a tick.
 *
 * <p>A batch starts {@code PENDING} and moves exactly once to {@code COMMITTED} or
 * {@code DROPPED}; a second commit of the same identity is refused and counted rather than
 * accepted. A drop always carries the code that ended the batch, so a batch can never disappear
 * without a reason.</p>
 *
 * <p>The ledger also carries the dispatch epoch. The merge advances it when a tick is closed, which
 * is what makes a result that arrives afterwards visibly late instead of silently applying to the
 * next frame.</p>
 */
public final class TaskLedger {

    /** The states one batch may be in. */
    public enum BatchState {
        /** Dispatched, no result merged yet. */
        PENDING,
        /** Merged and accepted exactly once. */
        COMMITTED,
        /** Ended without a merge, always with a code. */
        DROPPED
    }

    /** What one tick's accounting says. */
    public record ClosureReport(long dispatched, long executed, long retried, long fellback,
                                long cancelled, long failed, long committed, long dropped,
                                long pending, boolean ok) {

        /** @return a report that holds because nothing was dispatched */
        public static ClosureReport empty() {
            return new ClosureReport(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, true);
        }
    }

    private final Map<Long, BatchState> states = new LinkedHashMap<>();
    private final Map<Long, String> dropCodes = new LinkedHashMap<>();
    private long epoch;
    private long duplicateCommits;
    private long droppedWithoutCode;

    /** @param epoch the dispatch epoch the ledger starts in */
    public TaskLedger(long epoch) {
        this.epoch = epoch;
    }

    /** @return the dispatch epoch results are judged against */
    public synchronized long epoch() {
        return epoch;
    }

    /** Advances the dispatch epoch; called when a tick is closed. */
    public synchronized void advanceEpoch() {
        epoch++;
    }

    /**
     * Registers a batch as pending.
     *
     * @param batchId identity of the batch
     */
    public synchronized void register(long batchId) {
        states.putIfAbsent(batchId, BatchState.PENDING);
    }

    /**
     * Marks a batch as committed.
     *
     * @param batchId identity of the batch
     * @return {@code false} when the batch was already committed, which is a duplicate
     */
    public synchronized boolean markCommitted(long batchId) {
        BatchState current = states.get(batchId);
        if (current == BatchState.COMMITTED) {
            duplicateCommits++;
            return false;
        }
        states.put(batchId, BatchState.COMMITTED);
        dropCodes.remove(batchId);
        return true;
    }

    /**
     * Marks a batch as dropped.
     *
     * @param batchId identity of the batch
     * @param code    the code that ended it, never empty
     * @return {@code false} when no code was given, which is refused and counted
     */
    public synchronized boolean markDropped(long batchId, String code) {
        if (code == null || code.isEmpty()) {
            droppedWithoutCode++;
            return false;
        }
        states.put(batchId, BatchState.DROPPED);
        dropCodes.put(batchId, code);
        return true;
    }

    /**
     * Answers the state of one batch.
     *
     * @param batchId identity of the batch
     * @return the state, or {@code null} when the batch was never registered
     */
    public synchronized BatchState state(long batchId) {
        return states.get(batchId);
    }

    /**
     * Answers the code a dropped batch ended with.
     *
     * @param batchId identity of the batch
     * @return the code, or {@code null} when the batch was not dropped
     */
    public synchronized String dropCode(long batchId) {
        return dropCodes.get(batchId);
    }

    /** @return attempts to commit one batch twice */
    public synchronized long duplicateCommits() {
        return duplicateCommits;
    }

    /** @return drops refused because they carried no code */
    public synchronized long droppedWithoutCode() {
        return droppedWithoutCode;
    }

    /** @return how many batches are still pending */
    public synchronized long pendingCount() {
        long pending = 0L;
        for (BatchState state : states.values()) {
            if (state == BatchState.PENDING) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Builds the closure of one tick from the outcomes that were merged.
     *
     * @param dispatched batches the tick dispatched
     * @param executed   batches a worker executed
     * @param retried    batches a retryable fault ended early
     * @param fellback   batches a non-retryable fault ended early
     * @param cancelled  batches the deadline cancelled
     * @param failed     batches whose worker died
     * @return the report, with {@code ok} telling whether both closures hold
     */
    public synchronized ClosureReport closure(long dispatched, long executed, long retried,
                                              long fellback, long cancelled, long failed) {
        long committed = 0L;
        long dropped = 0L;
        long pending = 0L;
        for (BatchState state : states.values()) {
            if (state == BatchState.COMMITTED) {
                committed++;
            } else if (state == BatchState.DROPPED) {
                dropped++;
            } else {
                pending++;
            }
        }
        boolean dispatchCloses = dispatched == executed + retried + fellback + cancelled + failed;
        boolean batchCloses = committed + dropped + pending == dispatched;
        return new ClosureReport(dispatched, executed, retried, fellback, cancelled, failed,
            committed, dropped, pending, dispatchCloses && batchCloses);
    }

    /**
     * Closes the window of one merge: the states of a finished tick are dropped, while the
     * duplicate and missing-code counters stay. Batch identities never repeat, so a later tick
     * cannot inherit a state from an earlier one.
     */
    public synchronized void closeWindow() {
        states.clear();
        dropCodes.clear();
    }

    /** Clears the live states and the epoch; used by the readout reset and by tests. */
    public synchronized void reset() {
        states.clear();
        dropCodes.clear();
        epoch = 0L;
        duplicateCommits = 0L;
        droppedWithoutCode = 0L;
    }
}
