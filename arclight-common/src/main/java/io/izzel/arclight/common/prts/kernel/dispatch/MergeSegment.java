/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.ArrayList;
import java.util.List;

/** The walk is the frozen order of the plan. */
public final class MergeSegment {

    /** What one merge produced. */
    public record Frame(long tickIndex, long commitSeq, int committed, int redone, int cancelled,
                        int failed, boolean closureOk, long parallelHash, long serialHash,
                        boolean hashEqual) {

        public static Frame empty() {
            return new Frame(-1L, 0L, 0, 0, 0, 0, true, 0L, 0L, false);
        }
    }

    private volatile Thread ownerThread;
    private long commitSeq;
    private long foreignRuns;
    private long hashInconsistent;
    private List<StateHasher.Slice> lastCommitted = List.of();
    private DomainHash lastParallel;
    private boolean mergeSkipped;

    /** Names a tick that closed without a merge: the frame the segment still holds belongs to an
     * older tick, so the next read back counts it as skipped instead of judging a world left running. */
    public void noteSkippedMerge() {
        mergeSkipped = true;
    }

    /** Names the thread the segment may merge on. */
    public void bindOwnerThread(Thread thread) {
        if (ownerThread == null) {
            ownerThread = thread;
        }
    }

    /** Merges one dispatched pass, comparing the two arms for the domain alone. */
    public Frame merge(DispatchPass pass, long deadlineNanos, ArenaLedger arena,
                       DispatchReadings readings, DiffProbe probe, HashWhitelist whitelist,
                       String domainId, DispatchWriteBack writeBack) {
        return merge(pass, deadlineNanos, arena, readings, probe, null, whitelist, domainId,
            writeBack);
    }

    /** Merges one dispatched pass; the mirror carries the same pair to the module's differential. */
    public Frame merge(DispatchPass pass, long deadlineNanos, ArenaLedger arena,
                       DispatchReadings readings, DiffProbe probe, DiffProbe mirror,
                       HashWhitelist whitelist, String domainId, DispatchWriteBack writeBack) {
        Thread owner = ownerThread;
        if (owner != null && owner != Thread.currentThread()) {
            foreignRuns++;
            return null;
        }
        // The world is asked what it holds for the frame the commit has just reached: a committed
        // frame the world no longer agrees with is a frozen scene, not a difference to explain away.
        // Only the frame of the immediately preceding merge is judged - a tick without a merge left
        // the world to run on by itself, and that movement is not the settlement's difference.
        if (writeBack != null && !lastCommitted.isEmpty()) {
            if (mergeSkipped) {
                readings.noteReadBackSkipped();
            } else {
                DispatchWriteBack.ReadBack readBack = writeBack.readBack(lastCommitted, whitelist,
                    domainId, pass.plan().tickIndex());
                if (!readBack.equal()) {
                    hashInconsistent++;
                    readings.noteHashInconsistent();
                }
            }
        }
        mergeSkipped = false;
        long mergeStartedAt = System.nanoTime();
        List<TaskOutcome> outcomes = pass.awaitAll(deadlineNanos);
        // The wait and the work are two different costs of the same window: the first one blocks,
        // the second one works. They are kept apart so a window can be read as either.
        long waitNanos = System.nanoTime() - mergeStartedAt;
        long frameNanos = 0L;
        List<StateHasher.Slice> parallelSlices = new ArrayList<>();
        List<StateHasher.Slice> serialSlices = new ArrayList<>();
        // The read-back set of the next frame: only a batch the settlement handed to the world is
        // in it, so every collected row is either landed and read back next frame or counted as not
        // landed; a refused row reaches neither the equal count nor the inconsistency count.
        List<StateHasher.Slice> landedSlices = new ArrayList<>();
        int committed = 0;
        int redone = 0;
        int cancelled = 0;
        int failed = 0;
        int executed = 0;
        int retried = 0;
        int fellback = 0;
        for (int i = 0; i < pass.entries().size(); i++) {
            DispatchPass.Entry entry = pass.entries().get(i);
            TaskOutcome outcome = outcomes.get(i);
            switch (outcome.status()) {
                case EXECUTED -> executed++;
                case RETRIED -> retried++;
                case FELLBACK -> fellback++;
                case CANCELLED -> cancelled++;
                case FAILED -> failed++;
            }
            // The reference of this batch: the tick thread runs the same pure step the worker ran,
            // over the same rows of the same frozen view. It is the serial arm of the comparison and
            // at once the side a worker's answer is judged against, so the check and the comparison
            // can never read two different inputs. It exists only for the comparison and, when it
            // turns out to be the frame's own values, for the fallback below; either way its time
            // lands in the row of the work it actually did.
            ArenaScratch reference = EntityIntegrator.referenceScratch();
            long referenceStartedAt = System.nanoTime();
            EntityIntegrator.integrateRangeSerial(entry.view(), entry.batch().rangeStart(),
                entry.batch().rangeEnd(), reference);
            long referenceNanos = System.nanoTime() - referenceStartedAt;
            boolean workerValue = outcome.status() == TaskOutcome.Status.EXECUTED
                && entry.slot() != null && entry.slot().state() == ArenaSlot.State.PUBLISHED;
            if (workerValue && writeBack != null) {
                workerValue = writeBack.verify(entry.batch(), entry.lease().scratch(), reference);
            }
            ArenaScratch scratch;
            if (workerValue) {
                scratch = entry.lease().scratch();
                if (writeBack != null) {
                    DispatchWriteBack.noteEvidence(readings, entry.view().worldId(), referenceNanos);
                }
            } else {
                // Whatever kept the worker from answering - a cancel, a retry, a fallback, a full
                // queue, a dead thread, or a check that refused its values - the batch is the tick
                // thread's own here, in its own position of the frozen order, and the reference pass
                // above already computed exactly the values this step produces. A half applied batch
                // does not exist: either the frame carries the values or the batch is dropped.
                redone++;
                readings.noteTaskOnMain();
                scratch = reference;
                if (writeBack != null) {
                    DispatchWriteBack.noteRedo(readings, entry.view().worldId(),
                        entry.batch().task().regionId(), referenceNanos);
                }
            }
            long collectStartedAt = System.nanoTime();
            List<StateHasher.Slice> batchSlices = new ArrayList<>();
            collect(entry, scratch, batchSlices);
            readings.noteRows(batchSlices.size());
            parallelSlices.addAll(batchSlices);
            append(entry.batch().task(), entry.view(), reference, serialSlices);
            frameNanos += System.nanoTime() - collectStartedAt;
            // The settlement of the tick the frame belongs to: compute-only samples the world back
            // and lands nothing, takeover hands the batch to the channel the commit segment drains.
            // What was not handed over is not read back next merge - the world was never asked to
            // hold it - and its rows are counted as not landed instead.
            long settleStartedAt = System.nanoTime();
            if (writeBack != null) {
                if (writeBack.settle(entry.batch(), batchSlices).landed()) {
                    landedSlices.addAll(batchSlices);
                } else {
                    readings.noteWriteBackNotLanded(batchSlices.size());
                }
            } else {
                readings.noteWriteBackNotLanded(batchSlices.size());
            }
            if (!pass.ledger().markCommitted(entry.batch().batchId())) {
                readings.noteDuplicateCommit();
            } else {
                committed++;
                commitSeq++;
            }
            release(entry, arena, outcome.status() == TaskOutcome.Status.EXECUTED);
            frameNanos += System.nanoTime() - settleStartedAt;
        }
        // The row-level negative fixture: one row of the parallel arm's digest input is deviated
        // and nothing the settlement landed changes, so the two arms differ by exactly one row.
        int breakRow = FaultInjection.segmentBreakRow();
        if (breakRow > 0 && breakRow <= parallelSlices.size()) {
            int index = breakRow - 1;
            parallelSlices.set(index, deviated(parallelSlices.get(index)));
            readings.noteSegmentBreak();
        }
        long hashStartedAt = System.nanoTime();
        DomainHash parallel = StateHasher.hash(domainId, pass.plan().tickIndex(), parallelSlices,
            whitelist);
        DomainHash serial = StateHasher.hash(domainId, pass.plan().tickIndex(), serialSlices,
            whitelist);
        boolean equal = parallel.comparable() && serial.comparable()
            && parallel.value() == serial.value();
        probe.compare(parallel, serial);
        if (mirror != null) {
            mirror.compare(parallel, serial);
        }
        frameNanos += System.nanoTime() - hashStartedAt;
        readings.noteComputeWait(waitNanos);
        readings.noteComputeFrame(frameNanos);
        readings.noteCompute(waitNanos + frameNanos);
        readings.noteHashPair(equal);
        if (!equal && probe.report().firstForkTick() < 0) {
            readings.noteForkUnattributed();
        }
        lastCommitted = landedSlices;
        lastParallel = parallel;
        TaskLedger.ClosureReport closure = pass.ledger().closure(pass.dispatched(), executed,
            retried, fellback, cancelled, failed);
        pass.ledger().closeWindow();
        pass.ledger().advanceEpoch();
        return new Frame(pass.plan().tickIndex(), commitSeq, committed, redone, cancelled, failed,
            closure.ok(), parallel.value(), serial.value(), equal);
    }

    /** One row with its flag bit flipped; only the parallel list is replaced, never the frame the
     * settlement landed. */
    private static StateHasher.Slice deviated(StateHasher.Slice row) {
        return new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(), row.entitySeq(),
            row.x(), row.y(), row.z(), row.yaw(), row.pitch(), row.velX(), row.velY(), row.velZ(),
            row.flags() ^ 1L, row.slotGeneration(), row.segmentRef());
    }

    private static void release(DispatchPass.Entry entry, ArenaLedger arena,
                                boolean ownerConfirmed) {
        ArenaSlot.Lease lease = entry.lease();
        if (lease != null) {
            // Judged against the lease: a worker that already released is answered ALREADY_RELEASED,
            // and a slot handed to the next batch refuses the release. A cancelled batch does not
            // confirm its buffer, so a worker still in its body cannot write into the next owner's.
            arena.release(lease, ownerConfirmed);
        }
    }

    private static void collect(DispatchPass.Entry entry, ArenaScratch scratch,
                                List<StateHasher.Slice> slices) {
        if (!entry.batch().task().worldId().equals(entry.view().worldId())) {
            return;
        }
        append(entry.batch().task(), entry.view(), scratch, slices);
    }

    private static void append(WorkTask task, EntityCandidateView view, ArenaScratch scratch,
                               List<StateHasher.Slice> slices) {
        long segmentRef = StateHasher.mixString(StateHasher.LAYOUT_VERSION, task.worldId());
        int rows = Math.min(scratch.filled(), task.entityCount());
        for (int local = 0; local < rows; local++) {
            int row = task.entitySeqStart() + local;
            slices.add(new StateHasher.Slice(view.worldId(), task.regionId(), task.batchId(),
                view.entitySeq(row), scratch.posX(local), scratch.posY(local), scratch.posZ(local),
                scratch.yaw(local), scratch.pitch(local), scratch.velX(local), scratch.velY(local),
                scratch.velZ(local), scratch.flags(local), 0L, segmentRef));
        }
    }

    /** The parallel arm of the last merge; null before the first one. */
    public DomainHash lastParallel() {
        return lastParallel;
    }

    public long foreignRuns() {
        return foreignRuns;
    }

    public long hashInconsistent() {
        return hashInconsistent;
    }

    /** Clears the live counters; used by the readout reset and by tests. */
    public void reset() {
        commitSeq = 0L;
        foreignRuns = 0L;
        hashInconsistent = 0L;
        lastCommitted = List.of();
        lastParallel = null;
        mergeSkipped = false;
    }
}
