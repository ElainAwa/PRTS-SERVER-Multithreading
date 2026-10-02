/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

/** The one-way state of every batch, and the closure of a tick. A batch starts {@code PENDING} and
 * moves exactly once to {@code COMMITTED} or {@code DROPPED}; a second commit of the same identity
 * is refused and counted rather than accepted. */
public final class TaskLedger {

    /** The states one batch may be in. */
    public enum BatchState {
        PENDING,
        COMMITTED,
        DROPPED
    }

    /** What one tick's accounting says. */
    public record ClosureReport(long dispatched, long executed, long retried, long fellback,
                                long cancelled, long failed, long committed, long dropped,
                                long pending, boolean ok) {

        public static ClosureReport empty() {
            return new ClosureReport(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, true);
        }
    }

    private final Map<Long, BatchState> states = new LinkedHashMap<>();
    private final Map<Long, String> dropCodes = new LinkedHashMap<>();
    private long epoch;
    private long duplicateCommits;
    private long droppedWithoutCode;

    public TaskLedger(long epoch) {
        this.epoch = epoch;
    }

    public synchronized long epoch() {
        return epoch;
    }

    /** Advances the dispatch epoch; called when a tick is closed. */
    public synchronized void advanceEpoch() {
        epoch++;
    }

    /** Registers a batch as pending. */
    public synchronized void register(long batchId) {
        states.putIfAbsent(batchId, BatchState.PENDING);
    }

    /** Marks a batch as committed. */
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

    /** Marks a batch as dropped. */
    public synchronized boolean markDropped(long batchId, String code) {
        if (code == null || code.isEmpty()) {
            droppedWithoutCode++;
            return false;
        }
        states.put(batchId, BatchState.DROPPED);
        dropCodes.put(batchId, code);
        return true;
    }

    /** Answers the state of one batch. */
    public synchronized BatchState state(long batchId) {
        return states.get(batchId);
    }

    /** Answers the code a dropped batch ended with. */
    public synchronized String dropCode(long batchId) {
        return dropCodes.get(batchId);
    }

    public synchronized long duplicateCommits() {
        return duplicateCommits;
    }

    public synchronized long droppedWithoutCode() {
        return droppedWithoutCode;
    }

    public synchronized long pendingCount() {
        long pending = 0L;
        for (BatchState state : states.values()) {
            if (state == BatchState.PENDING) {
                pending++;
            }
        }
        return pending;
    }

    /** Builds the closure of one tick from the outcomes that were merged. */
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

    /** Closes the window of one merge: the states of a finished tick are dropped, while the
     * duplicate and missing-code counters stay. */
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
