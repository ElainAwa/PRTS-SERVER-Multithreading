/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What one process's dispatch did, published as proposed readings rather than an approved counter
 * table.
 *
 * <p>Every value is readable at zero, which is what makes the switch-off leg a reading instead of an
 * absence: the same line is rendered with the pool never created and every count at zero. The
 * per-thread counts are the thread-identity evidence - a batch that ran on a worker is counted under
 * that worker's name - and the closure flag is the arithmetic that proves the five statuses account
 * for every dispatched batch.</p>
 */
public final class DispatchReadings {

    private final LongAdder tasksTotal = new LongAdder();
    private final LongAdder dispatched = new LongAdder();
    private final LongAdder executed = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder fellback = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder lateResultDropped = new LongAdder();
    private final LongAdder backpressure = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder duplicateCommit = new LongAdder();
    private final LongAdder droppedWithoutCode = new LongAdder();
    private final LongAdder poolOpenFailed = new LongAdder();
    private final LongAdder threadsStarted = new LongAdder();
    private final LongAdder threadsRetired = new LongAdder();
    private final LongAdder entityNanos = new LongAdder();
    private final LongAdder hashPairs = new LongAdder();
    private final LongAdder hashEqual = new LongAdder();
    private final LongAdder forkUnattributed = new LongAdder();
    private final LongAdder hashInconsistent = new LongAdder();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger queuePeak = new AtomicInteger();
    private final AtomicLong planClockReads = new AtomicLong();
    private final ConcurrentHashMap<String, LongAdder> execByThread = new ConcurrentHashMap<>();
    private final Map<String, Long> execSnapshot = new TreeMap<>();

    /** Counts the tasks one plan froze. */
    public void noteTasks(int count) {
        tasksTotal.add(count);
    }

    /** Counts one dispatched batch. */
    public void noteDispatched() {
        dispatched.increment();
    }

    /** Counts one worker execution and the time it took. */
    public void noteExecution(String threadName, long nanos) {
        entityNanos.add(nanos);
        execByThread.computeIfAbsent(threadName, key -> new LongAdder()).increment();
    }

    /**
     * Counts one terminal outcome.
     *
     * @param outcome the outcome to count
     */
    public void noteOutcome(TaskOutcome outcome) {
        switch (outcome.status()) {
            case EXECUTED -> executed.increment();
            case RETRIED -> retried.increment();
            case FELLBACK -> fellback.increment();
            case CANCELLED -> cancelled.increment();
            case FAILED -> failed.increment();
        }
    }

    /** Counts a result that arrived after its epoch was closed. */
    public void noteLateResult() {
        lateResultDropped.increment();
    }

    /** Counts a batch that could not enter the queue because it was full. */
    public void noteBackpressure() {
        backpressure.increment();
    }

    /** Counts a batch the deadline cancelled. */
    public void noteTimeout() {
        timeouts.increment();
    }

    /** Counts a second commit of one batch identity. */
    public void noteDuplicateCommit() {
        duplicateCommit.increment();
    }

    /** Counts a drop that carried no code. */
    public void noteDroppedWithoutCode() {
        droppedWithoutCode.increment();
    }

    /** Counts a pool that could not be opened. */
    public void notePoolOpenFailed() {
        poolOpenFailed.increment();
    }

    /** Counts one started worker thread. */
    public void noteThreadStarted() {
        threadsStarted.increment();
    }

    /** Counts one retired worker thread. */
    public void noteThreadRetired() {
        threadsRetired.increment();
    }

    /** Records the queue depth and its high-water mark. */
    public void noteQueueDepth(int depth) {
        queueDepth.set(depth);
        queuePeak.accumulateAndGet(depth, Math::max);
    }

    /**
     * Records one comparison of the two arms.
     *
     * @param equal whether the two hashes were equal
     */
    public void noteHashPair(boolean equal) {
        hashPairs.increment();
        if (equal) {
            hashEqual.increment();
        }
    }

    /** Counts a mismatch the descent could not place. */
    public void noteForkUnattributed() {
        forkUnattributed.increment();
    }

    /** Counts a committed frame whose hash no longer matched when it was re-taken. */
    public void noteHashInconsistent() {
        hashInconsistent.increment();
    }

    /** @return committed frames whose hash no longer matched */
    public long hashInconsistent() {
        return hashInconsistent.sum();
    }

    /** Counts a clock read taken in the planning phase; must stay zero. */
    public void notePlanClockRead() {
        planClockReads.incrementAndGet();
    }

    /** @return the clock reads planning performed; must stay zero */
    public long planClockReads() {
        return planClockReads.get();
    }

    /** @return the tasks frozen since the last reset */
    public long tasksTotal() {
        return tasksTotal.sum();
    }

    /** @return the batches dispatched since the last reset */
    public long dispatched() {
        return dispatched.sum();
    }

    /** @return the batches a worker executed */
    public long executed() {
        return executed.sum();
    }

    /** @return the batches a retryable fault ended early */
    public long retried() {
        return retried.sum();
    }

    /** @return the batches a non-retryable fault ended early */
    public long fellback() {
        return fellback.sum();
    }

    /** @return the batches the deadline cancelled */
    public long cancelled() {
        return cancelled.sum();
    }

    /** @return the batches whose worker died */
    public long failed() {
        return failed.sum();
    }

    /** @return the late results that were dropped and counted */
    public long lateResultDropped() {
        return lateResultDropped.sum();
    }

    /** @return the batches refused by a full queue */
    public long backpressure() {
        return backpressure.sum();
    }

    /** @return the deadlines that cancelled at least one batch */
    public long timeouts() {
        return timeouts.sum();
    }

    /** @return the duplicate commits that were refused */
    public long duplicateCommit() {
        return duplicateCommit.sum();
    }

    /** @return the drops that carried no code */
    public long droppedWithoutCode() {
        return droppedWithoutCode.sum();
    }

    /** @return the pools that could not be opened */
    public long poolOpenFailed() {
        return poolOpenFailed.sum();
    }

    /** @return the worker threads started */
    public long threadsStarted() {
        return threadsStarted.sum();
    }

    /** @return the worker threads retired */
    public long threadsRetired() {
        return threadsRetired.sum();
    }

    /** @return the nanoseconds workers spent in batch bodies */
    public long entityNanos() {
        return entityNanos.sum();
    }

    /** @return the pairs of hashes compared */
    public long hashPairs() {
        return hashPairs.sum();
    }

    /** @return the pairs of hashes that were equal */
    public long hashEqual() {
        return hashEqual.sum();
    }

    /** @return the mismatches the descent could not place */
    public long forkUnattributed() {
        return forkUnattributed.sum();
    }

    /** @return the queue depth of the last submission */
    public int queueDepth() {
        return queueDepth.get();
    }

    /** @return the high-water mark of the queue depth */
    public int queuePeak() {
        return queuePeak.get();
    }

    /**
     * Answers the executions one worker thread performed.
     *
     * @param threadName the thread name, for example the pool prefix and its index
     * @return the executions, or zero when that thread never ran a batch
     */
    public long execByThread(String threadName) {
        LongAdder adder = execByThread.get(threadName);
        return adder == null ? 0L : adder.sum();
    }

    /** @return a stable copy of the per-thread execution counts */
    public synchronized Map<String, Long> execByThreadSnapshot() {
        execSnapshot.clear();
        for (Map.Entry<String, LongAdder> entry : new TreeMap<>(execByThread).entrySet()) {
            execSnapshot.put(entry.getKey(), entry.getValue().sum());
        }
        return Map.copyOf(execSnapshot);
    }

    /**
     * Renders the evidence line of one window.
     *
     * @param arena the arena ledger the line reports with
     * @param threadsAlive how many worker threads are alive right now
     * @param closureOk whether both closures held for the last tick
     * @return the line, with every value present even when it is zero
     */
    public String evidenceLine(ArenaLedger arena, int threadsAlive, boolean closureOk) {
        StringBuilder builder = new StringBuilder("[PRTS] dispatch: ");
        builder.append("tasks_total=").append(tasksTotal());
        builder.append(" dispatched=").append(dispatched());
        builder.append(" executed=").append(executed());
        builder.append(" retried=").append(retried());
        builder.append(" fellback=").append(fellback());
        builder.append(" cancelled=").append(cancelled());
        builder.append(" failed=").append(failed());
        builder.append(" late_dropped=").append(lateResultDropped());
        builder.append(" backpressure=").append(backpressure());
        builder.append(" timeouts=").append(timeouts());
        builder.append(" duplicate_commit=").append(duplicateCommit());
        builder.append(" dropped_without_code=").append(droppedWithoutCode());
        builder.append(" pool_open_failed=").append(poolOpenFailed());
        builder.append(" threads_started=").append(threadsStarted());
        builder.append(" threads_alive=").append(threadsAlive);
        builder.append(" threads_retired=").append(threadsRetired());
        builder.append(" queue_depth=").append(queueDepth());
        builder.append(" queue_peak=").append(queuePeak());
        builder.append(" closure=").append(closureOk ? "ok" : "broken");
        builder.append(" hash_pairs=").append(hashPairs());
        builder.append(" hash_equal=").append(hashEqual());
        builder.append(" fork_unattributed=").append(forkUnattributed());
        builder.append(" hash_inconsistent=").append(hashInconsistent());
        builder.append(" arena_claims=").append(arena.claims());
        builder.append(" arena_releases=").append(arena.releases());
        builder.append(" arena_pinned=").append(arena.pinnedCount());
        builder.append(" arena_generation_bumps=").append(arena.generationBumps());
        builder.append(" arena_foreign_writes=").append(arena.foreignWrites());
        builder.append(" plan_clock_reads=").append(planClockReads());
        for (Map.Entry<String, Long> entry : execByThreadSnapshot().entrySet()) {
            builder.append(" exec_by_thread.").append(entry.getKey()).append("=")
                .append(entry.getValue());
        }
        return builder.toString();
    }

    /** Clears every reading. */
    public void reset() {
        tasksTotal.reset();
        dispatched.reset();
        executed.reset();
        retried.reset();
        fellback.reset();
        cancelled.reset();
        failed.reset();
        lateResultDropped.reset();
        backpressure.reset();
        timeouts.reset();
        duplicateCommit.reset();
        droppedWithoutCode.reset();
        poolOpenFailed.reset();
        threadsStarted.reset();
        threadsRetired.reset();
        entityNanos.reset();
        hashPairs.reset();
        hashEqual.reset();
        forkUnattributed.reset();
        hashInconsistent.reset();
        queueDepth.set(0);
        queuePeak.set(0);
        planClockReads.set(0L);
        execByThread.clear();
    }
}