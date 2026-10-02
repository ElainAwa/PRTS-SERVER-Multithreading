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
    private final LongAdder lateEpochDropped = new LongAdder();
    private final LongAdder backpressure = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder duplicateCommit = new LongAdder();
    private final LongAdder droppedWithoutCode = new LongAdder();
    private final LongAdder poolOpenFailed = new LongAdder();
    private final LongAdder shutdowns = new LongAdder();
    private final LongAdder shutdownUnterminated = new LongAdder();
    private final LongAdder shutdownDropped = new LongAdder();
    private final AtomicInteger shutdownRemaining = new AtomicInteger();
    private final LongAdder threadsStarted = new LongAdder();
    private final LongAdder threadsRetired = new LongAdder();
    private final LongAdder entityNanos = new LongAdder();
    private final LongAdder tasksOnMain = new LongAdder();
    private final LongAdder redoNanos = new LongAdder();
    private final LongAdder verifyNanos = new LongAdder();
    private final LongAdder computeNanos = new LongAdder();
    private final LongAdder verifyRows = new LongAdder();
    private final LongAdder verifyMismatch = new LongAdder();
    private final LongAdder entityNanosSerialArm = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder writeBackNanos = new LongAdder();
    private final LongAdder writeBackBatches = new LongAdder();
    private final LongAdder writeBackRows = new LongAdder();
    private final LongAdder writeBackGone = new LongAdder();
    private final LongAdder writeBackRefused = new LongAdder();
    private final LongAdder writeBackStale = new LongAdder();
    private final LongAdder writeBackNoRows = new LongAdder();
    private final LongAdder writeBackIdentical = new LongAdder();
    private final LongAdder writeBackKept = new LongAdder();
    private final LongAdder readBackKept = new LongAdder();
    private final LongAdder readBackPairs = new LongAdder();
    private final LongAdder readBackEqual = new LongAdder();
    private final LongAdder readBackRows = new LongAdder();
    private final LongAdder readBackGone = new LongAdder();
    private final LongAdder hashPairs = new LongAdder();
    private final LongAdder hashEqual = new LongAdder();
    private final LongAdder forkUnattributed = new LongAdder();
    private final LongAdder hashInconsistent = new LongAdder();
    private volatile int lastPlanTasks;
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger queuePeak = new AtomicInteger();
    private final AtomicLong planClockReads = new AtomicLong();
    private final ConcurrentHashMap<String, LongAdder> execByThread = new ConcurrentHashMap<>();
    private final Map<String, Long> execSnapshot = new TreeMap<>();

    /** Counts the tasks one plan froze. */
    public void noteTasks(int count) {
        tasksTotal.add(count);
        lastPlanTasks = count;
    }

    /** @return the task count of the most recently frozen plan */
    public int lastPlanTasks() {
        return lastPlanTasks;
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

    public void noteLateEpochResult() {
        lateEpochDropped.increment();
    }

    /** Counts one batch the tick thread had to compute itself. */
    public void noteTaskOnMain() {
        tasksOnMain.increment();
    }

    /**
     * Counts the time the tick thread spent recomputing one batch itself.
     *
     * <p>This is the fallback row and nothing else: a batch the worker did not answer is computed
     * here, in its own position of the frozen order. The equivalence check of an answered batch is
     * a different job and is counted by {@link #noteVerify(long)}, so a reading of this row is a
     * statement about fallbacks rather than about the cost of the check.</p>
     *
     * @param nanos the duration
     */
    public void noteRedo(long nanos) {
        if (nanos > 0L) {
            redoNanos.add(nanos);
        }
    }

    /**
     * Counts the time the tick thread spent checking what a worker answered.
     *
     * <p>The check runs in the merge of the tick the frame belongs to and on the thread that owns
     * the tick, so it is main-thread work of the entity domain like the fallback; keeping the two
     * in one row is what left the cost of the check unreadable.</p>
     *
     * @param nanos the duration
     */
    public void noteVerify(long nanos) {
        if (nanos > 0L) {
            verifyNanos.add(nanos);
        }
    }

    /**
     * Counts the time the tick thread spent waiting for and merging the frame.
     *
     * <p>It is the wait for the workers plus the frozen-order walk and the two hashes. The wait is
     * deliberately not added to any self class row: a wait is not work the entity domain did, and
     * the class rows are where the share of the tick is accounted.</p>
     *
     * @param nanos the duration
     */
    public void noteCompute(long nanos) {
        if (nanos > 0L) {
            computeNanos.add(nanos);
        }
    }

    /**
     * Counts the rows one batch check folded.
     *
     * <p>The rows are what the check's time is spent on, so a window that reports the check and its
     * rows can be read as a cost per row instead of as a total that depends on how long the window
     * happened to be.</p>
     *
     * @param rows the rows of the batch the check folded
     */
    public void noteVerifyRows(int rows) {
        if (rows > 0) {
            verifyRows.add(rows);
        }
    }

    /** Counts one batch the check refused and the tick thread therefore recomputed. */
    public void noteVerifyMismatch() {
        verifyMismatch.increment();
    }

    /** Counts the time the tick thread spent computing the serial arm of the comparison. */
    public void noteSerialArm(long nanos) {
        if (nanos > 0L) {
            entityNanosSerialArm.add(nanos);
        }
    }

    /** Counts the time the tick thread spent reading the entity candidates of one tick. */
    public void noteSnapshot(long nanos) {
        if (nanos > 0L) {
            snapshotNanos.add(nanos);
        }
    }

    /**
     * Counts one write-back payload the commit applied.
     *
     * @param written entities the payload wrote
     * @param gone    entities the payload found gone
     * @param nanos   the time the application took
     */
    public void noteWriteBack(int written, int gone, long nanos) {
        writeBackBatches.increment();
        writeBackRows.add(written);
        if (nanos > 0L) {
            writeBackNanos.add(nanos);
        }
        if (gone > 0) {
            writeBackGone.add(gone);
        }
    }

    /** Counts the rows of one batch handed to the intent channel. */
    public void noteWriteBackEnqueued(int rows) {
        writeBackRows.add(rows);
    }

    /** Counts a batch whose write-back the channel refused at its depth limit. */
    public void noteWriteBackRefused() {
        writeBackRefused.increment();
    }

    /**
     * Counts the rows of one commit the takeover boundary decided about.
     *
     * @param identical rows the world already held bit for bit and the leg left untouched
     * @param kept      rows that differed and stayed with the host path
     */
    public void noteWriteBackIdentity(int identical, int kept) {
        if (identical > 0) {
            writeBackIdentical.add(identical);
        }
        if (kept > 0) {
            writeBackKept.add(kept);
        }
    }

    /**
     * Counts the rows one read back left out because the takeover boundary kept them.
     *
     * @param kept rows the world was not asked about
     */
    public void noteReadBackKept(int kept) {
        if (kept > 0) {
            readBackKept.add(kept);
        }
    }

    /**
     * Counts one read back of a committed frame.
     *
     * @param rows  rows the world still held
     * @param gone  rows whose entity was no longer in its level
     * @param equal whether the world held exactly the values the frame committed
     */
    public void noteReadBack(int rows, int gone, boolean equal) {
        readBackPairs.increment();
        readBackRows.add(rows);
        readBackGone.add(gone);
        if (equal) {
            readBackEqual.increment();
        }
    }

    public void noteWriteBackStale() {
        writeBackStale.increment();
    }

    public void noteWriteBackNoRows() {
        writeBackNoRows.increment();
    }

    public void noteShutdownDropped() {
        shutdownDropped.increment();
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

    /** Counts one bounded shutdown; a wait that did not confirm is not silent. */

    public void noteShutdown(int remaining, boolean terminated) {
        shutdowns.increment();
        shutdownRemaining.set(Math.max(0, remaining));
        if (!terminated || remaining > 0) {
            shutdownUnterminated.increment();
        }
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

    public long lateEpochDropped() {
        return lateEpochDropped.sum();
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

    public long shutdowns() {
        return shutdowns.sum();
    }

    public int shutdownRemaining() {
        return shutdownRemaining.get();
    }

    public long shutdownUnterminated() {
        return shutdownUnterminated.sum();
    }

    public long shutdownDropped() {
        return shutdownDropped.sum();
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

    /** @return the batches the tick thread had to compute itself */
    public long tasksOnMain() {
        return tasksOnMain.sum();
    }

    /** @return the nanoseconds the tick thread spent recomputing batches itself */
    public long redoNanos() {
        return redoNanos.sum();
    }

    /** @return the nanoseconds the tick thread spent checking what the workers answered */
    public long verifyNanos() {
        return verifyNanos.sum();
    }

    /** @return the nanoseconds the tick thread spent waiting for and merging the frame */
    public long computeNanos() {
        return computeNanos.sum();
    }

    /** @return the rows the checks folded */
    public long verifyRows() {
        return verifyRows.sum();
    }

    /** @return the batches the check refused and the tick thread recomputed */
    public long verifyMismatch() {
        return verifyMismatch.sum();
    }

    /** @return the nanoseconds the tick thread spent recomputing and checking batches itself */
    public long entityNanosMain() {
        return redoNanos() + verifyNanos();
    }

    /** @return the nanoseconds the tick thread spent computing the serial arm */
    public long entityNanosSerialArm() {
        return entityNanosSerialArm.sum();
    }

    /** @return the nanoseconds the tick thread spent reading the entity candidates */
    public long snapshotNanos() {
        return snapshotNanos.sum();
    }

    /** @return the nanoseconds the commit spent applying write-back payloads */
    public long writeBackNanos() {
        return writeBackNanos.sum();
    }

    /** @return the write-back payloads the commit applied */
    public long writeBackBatches() {
        return writeBackBatches.sum();
    }

    /** @return the rows handed to the intent channel for writing back */
    public long writeBackEnqueued() {
        return writeBackRows.sum();
    }

    /** @return the rows the write-back found gone from their level */
    public long writeBackGone() {
        return writeBackGone.sum();
    }

    /** @return the batches whose write-back the channel refused */
    public long writeBackRefused() {
        return writeBackRefused.sum();
    }

    public long writeBackStale() {
        return writeBackStale.sum();
    }

    public long writeBackNoRows() {
        return writeBackNoRows.sum();
    }

    /** @return the rows the takeover boundary found already held by the world */
    public long writeBackIdentical() {
        return writeBackIdentical.sum();
    }

    /** @return the rows the takeover boundary left with the host path */
    public long writeBackKept() {
        return writeBackKept.sum();
    }

    /** @return the committed frames the world was asked about */
    public long readBackPairs() {
        return readBackPairs.sum();
    }

    /** @return the committed frames the world still held exactly */
    public long readBackEqual() {
        return readBackEqual.sum();
    }

    /** @return the rows the read back compared */
    public long readBackRows() {
        return readBackRows.sum();
    }

    /** @return the rows whose entity was gone when the read back looked */
    public long readBackGone() {
        return readBackGone.sum();
    }

    /** @return the rows the takeover boundary left out of the read back */
    public long readBackKept() {
        return readBackKept.sum();
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
     * The values of one window that belong to the segments the dispatcher does not own.
     *
     * <p>The line is exported at one moment, so the commit cursor, the channel depth and the self row
     * are read here rather than assembled from several moments afterwards. That is what lets the
     * closure of the write-back leg - the tasks the plan froze, the orders the commit consumed and the
     * rows the world was asked about - be read as one statement instead of a join a reader would have
     * to make.</p>
     *
     * @param threadsAlive   how many worker threads are alive right now
     * @param closureOk      whether both closures held for the last tick
     * @param commitCursor   the orders the commit segment consumed
     * @param intentPending  intents still waiting in the channel
     * @param orderViolations commits refused because the frozen order did not match
     * @param selfEntityMs   the entity row of the self timer, in milliseconds
     */
    public record Window(int threadsAlive, boolean closureOk, long commitCursor, int intentPending,
                         long orderViolations, double selfEntityMs) {

        /** @return a window of a process that never dispatched */
        public static Window idle() {
            return new Window(0, true, 0L, 0, 0L, 0.0);
        }
    }

    /**
     * Renders the evidence line of one window.
     *
     * @param arena  the arena ledger the line reports with
     * @param window the values of the segments next to the dispatcher
     * @return the line, with every value present even when it is zero
     */
    public String evidenceLine(ArenaLedger arena, Window window) {
        int threadsAlive = window.threadsAlive();
        boolean closureOk = window.closureOk();
        StringBuilder builder = new StringBuilder("[PRTS] dispatch: ");
        builder.append("tasks_total=").append(tasksTotal());
        builder.append(" dispatched=").append(dispatched());
        builder.append(" executed=").append(executed());
        builder.append(" retried=").append(retried());
        builder.append(" fellback=").append(fellback());
        builder.append(" cancelled=").append(cancelled());
        builder.append(" failed=").append(failed());
        builder.append(" late_dropped=").append(lateResultDropped());
        builder.append(" late_epoch_dropped=").append(lateEpochDropped());
        builder.append(" backpressure=").append(backpressure());
        builder.append(" timeouts=").append(timeouts());
        builder.append(" duplicate_commit=").append(duplicateCommit());
        builder.append(" dropped_without_code=").append(droppedWithoutCode());
        builder.append(" pool_open_failed=").append(poolOpenFailed());
        builder.append(" shutdowns=").append(shutdowns());
        builder.append(" shutdown_remaining=").append(shutdownRemaining());
        builder.append(" shutdown_unterminated=").append(shutdownUnterminated());
        builder.append(" shutdown_dropped=").append(shutdownDropped());
        builder.append(" threads_started=").append(threadsStarted());
        builder.append(" threads_alive=").append(threadsAlive);
        builder.append(" threads_retired=").append(threadsRetired());
        builder.append(" queue_depth=").append(queueDepth());
        builder.append(" queue_peak=").append(queuePeak());
        builder.append(" closure=").append(closureOk ? "ok" : "broken");
        builder.append(" tasks_on_main=").append(tasksOnMain());
        builder.append(" entity_ms=").append(ms(entityNanosMain() + snapshotNanos()
            + writeBackNanos()));
        builder.append(" entity_ms_serial_arm=").append(ms(entityNanosSerialArm() + snapshotNanos()
            + writeBackNanos()));
        builder.append(" entity_ms_redo=").append(ms(redoNanos()));
        builder.append(" entity_ms_verify=").append(ms(verifyNanos()));
        builder.append(" verify_rows=").append(verifyRows());
        builder.append(" verify_mismatch=").append(verifyMismatch());
        builder.append(" dispatch_snapshot_ms=").append(ms(snapshotNanos()));
        builder.append(" dispatch_verify_ms=").append(ms(verifyNanos()));
        builder.append(" dispatch_compute_ms=").append(ms(computeNanos()));
        builder.append(" dispatch_redo_ms=").append(ms(redoNanos()));
        builder.append(" entity_ms_serial_integrate=").append(ms(entityNanosSerialArm()));
        builder.append(" entity_ms_on_worker=").append(ms(entityNanos()));
        builder.append(" entity_ms_snapshot=").append(ms(snapshotNanos()));
        builder.append(" writeback_ms=").append(ms(writeBackNanos()));
        builder.append(" writeback_batches=").append(writeBackBatches());
        builder.append(" writeback_rows=").append(writeBackEnqueued());
        builder.append(" writeback_gone=").append(writeBackGone());
        builder.append(" writeback_refused=").append(writeBackRefused());
        builder.append(" writeback_stale=").append(writeBackStale());
        builder.append(" writeback_no_rows=").append(writeBackNoRows());
        builder.append(" writeback_identical=").append(writeBackIdentical());
        builder.append(" writeback_kept=").append(writeBackKept());
        builder.append(" readback_pairs=").append(readBackPairs());
        builder.append(" readback_equal=").append(readBackEqual());
        builder.append(" readback_rows=").append(readBackRows());
        builder.append(" readback_gone=").append(readBackGone());
        builder.append(" readback_kept=").append(readBackKept());
        builder.append(" tasks_last_plan=").append(lastPlanTasks);
        builder.append(" commit_cursor=").append(window.commitCursor());
        builder.append(" intent_pending=").append(window.intentPending());
        builder.append(" order_violations=").append(window.orderViolations());
        builder.append(" self_entity_ms=").append(format(window.selfEntityMs()));
        builder.append(" hash_pairs=").append(hashPairs());
        builder.append(" hash_equal=").append(hashEqual());
        builder.append(" fork_unattributed=").append(forkUnattributed());
        builder.append(" hash_inconsistent=").append(hashInconsistent());
        builder.append(" arena_claims=").append(arena.claims());
        builder.append(" arena_releases=").append(arena.releases());
        builder.append(" arena_pinned=").append(arena.pinnedCount());
        builder.append(" arena_generation_bumps=").append(arena.generationBumps());
        builder.append(" arena_foreign_writes=").append(arena.foreignWrites());
        builder.append(" arena_stale_releases=").append(arena.staleReleases());
        builder.append(" arena_repeat_releases=").append(arena.repeatReleases());
        builder.append(" arena_quarantined=").append(arena.quarantinedSlots());
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
        lateEpochDropped.reset();
        backpressure.reset();
        timeouts.reset();
        duplicateCommit.reset();
        droppedWithoutCode.reset();
        poolOpenFailed.reset();
        shutdowns.reset();
        shutdownUnterminated.reset();
        shutdownDropped.reset();
        shutdownRemaining.set(0);
        threadsStarted.reset();
        threadsRetired.reset();
        entityNanos.reset();
        tasksOnMain.reset();
        redoNanos.reset();
        verifyNanos.reset();
        computeNanos.reset();
        verifyRows.reset();
        verifyMismatch.reset();
        entityNanosSerialArm.reset();
        snapshotNanos.reset();
        writeBackNanos.reset();
        writeBackBatches.reset();
        writeBackRows.reset();
        writeBackGone.reset();
        writeBackRefused.reset();
        writeBackStale.reset();
        writeBackNoRows.reset();
        writeBackIdentical.reset();
        writeBackKept.reset();
        readBackKept.reset();
        readBackPairs.reset();
        readBackEqual.reset();
        readBackRows.reset();
        readBackGone.reset();
        hashPairs.reset();
        hashEqual.reset();
        forkUnattributed.reset();
        hashInconsistent.reset();
        lastPlanTasks = 0;
        queueDepth.set(0);
        queuePeak.set(0);
        planClockReads.set(0L);
        execByThread.clear();
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}