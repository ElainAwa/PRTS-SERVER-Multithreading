/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;

import java.util.ArrayList;
import java.util.List;

/**
 * The merge: the only place a dispatched result is accepted, on the thread that owns the tick.
 *
 * <p>The walk is the frozen order of the plan. A batch whose worker answered is committed once and
 * its slot is released after its values were read; a batch that was cancelled, retried, fell back,
 * lost its worker or answered with values the check refused is the tick thread's own in the same
 * position, so the order of the frame never depends on which worker was fast enough. A second commit
 * of one identity is refused and counted.</p>
 *
 * <p>The reference of a batch is computed here, in the batch's own position, and it is both the
 * serial arm the parallel frame is compared with and the side the worker's answer is judged against;
 * a batch the check refuses is redone from that same computation instead of a second one. The
 * comparison is a reader: it can refuse to call two arms equal, never make them equal.</p>
 *
 * <p>What the frame becomes is the settlement's decision and it is taken here, in the merge of the
 * tick the frame belongs to: the compute-only settlement reads the world back and lands nothing, so
 * the state stays with the host; the takeover settlement hands the batch to the intent channel. No
 * settlement carries a frame into a later tick.</p>
 */
public final class MergeSegment {

    /**
     * What one merge produced.
     *
     * @param tickIndex    the tick the frame belongs to
     * @param commitSeq    how many batches were committed since the segment was created
     * @param committed    batches committed in this frame
     * @param redone       batches the tick thread had to redo
     * @param cancelled    batches the deadline cancelled
     * @param failed       batches whose worker died
     * @param closureOk    whether both closures held for this tick
     * @param parallelHash the hash of the parallel frame
     * @param serialHash   the hash of the serial reference
     * @param hashEqual    whether the two hashes were equal
     */
    public record Frame(long tickIndex, long commitSeq, int committed, int redone, int cancelled,
                        int failed, boolean closureOk, long parallelHash, long serialHash,
                        boolean hashEqual) {

        /** @return a frame no merge produced */
        public static Frame empty() {
            return new Frame(-1L, 0L, 0, 0, 0, 0, true, 0L, 0L, false);
        }
    }

    private volatile Thread ownerThread;
    private long commitSeq;
    private long foreignRuns;
    private long hashInconsistent;
    private List<StateHasher.Slice> lastCommitted = List.of();

    /**
     * Names the thread the segment may merge on.
     *
     * @param thread the thread that drives the tick
     */
    public void bindOwnerThread(Thread thread) {
        if (ownerThread == null) {
            ownerThread = thread;
        }
    }

    /**
     * Merges one dispatched pass.
     *
     * @param pass        the pass to merge
     * @param deadlineNanos the monotonic instant the wait for the workers ends
     * @param arena       the arena the slots were claimed from
     * @param readings    where the merge publishes
     * @param probe       the comparison of the two arms
     * @param whitelist   the fields the frame hash folds
     * @param domainId    the domain the frame belongs to
     * @param writeBack   the write-back leg, or {@code null} when the merge is not wired to one
     * @return the frame, or {@code null} when the calling thread is not the owner
     */
    public Frame merge(DispatchPass pass, long deadlineNanos, ArenaLedger arena,
                       DispatchReadings readings, DiffProbe probe, HashWhitelist whitelist,
                       String domainId, DispatchWriteBack writeBack) {
        Thread owner = ownerThread;
        if (owner != null && owner != Thread.currentThread()) {
            foreignRuns++;
            return null;
        }
        // The world is asked what it holds for the frame the commit has just reached, before the new
        // frame is built. A committed frame the world no longer agrees with is a frozen scene, not a
        // difference to explain away; re-hashing the same in-memory list would only prove itself.
        if (writeBack != null && !lastCommitted.isEmpty()) {
            DispatchWriteBack.ReadBack readBack = writeBack.readBack(lastCommitted, whitelist,
                domainId, pass.plan().tickIndex());
            if (!readBack.equal()) {
                hashInconsistent++;
                readings.noteHashInconsistent();
            }
        }
        long mergeStartedAt = System.nanoTime();
        List<TaskOutcome> outcomes = pass.awaitAll(deadlineNanos);
        long computeNanos = System.nanoTime() - mergeStartedAt;
        List<StateHasher.Slice> parallelSlices = new ArrayList<>();
        List<StateHasher.Slice> serialSlices = new ArrayList<>();
        int committed = 0;
        int redone = 0;
        int cancelled = 0;
        int failed = 0;
        int executed = 0;
        int retried = 0;
        int fellback = 0;
        boolean landed = false;
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
                // queue, a dead thread, or a check that refused the values it answered with - the
                // batch is the tick thread's own here, in its own position of the frozen order. The
                // reference pass above already computed exactly the values this step produces, so
                // the frame carries those instead of computing them a second time. A half applied
                // batch does not exist: either the frame carries the values or the batch is dropped
                // with its code.
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
            parallelSlices.addAll(batchSlices);
            append(entry.batch().task(), entry.view(), reference, serialSlices);
            computeNanos += System.nanoTime() - collectStartedAt;
            // The settlement of the tick the frame belongs to: compute-only samples the world back
            // and lands nothing, takeover hands the batch to the channel the commit segment drains.
            // A frame that was not handed over is not read back at the next merge, because the world
            // was never asked to hold it.
            long settleStartedAt = System.nanoTime();
            if (writeBack != null && writeBack.settle(entry.batch(), batchSlices).landed()) {
                landed = true;
            }
            if (!pass.ledger().markCommitted(entry.batch().batchId())) {
                readings.noteDuplicateCommit();
            } else {
                committed++;
                commitSeq++;
            }
            release(entry, arena, outcome.status() == TaskOutcome.Status.EXECUTED);
            computeNanos += System.nanoTime() - settleStartedAt;
        }
        long hashStartedAt = System.nanoTime();
        DomainHash parallel = StateHasher.hash(domainId, pass.plan().tickIndex(), parallelSlices,
            whitelist);
        DomainHash serial = StateHasher.hash(domainId, pass.plan().tickIndex(), serialSlices,
            whitelist);
        boolean equal = parallel.comparable() && serial.comparable()
            && parallel.value() == serial.value();
        probe.compare(parallel, serial);
        computeNanos += System.nanoTime() - hashStartedAt;
        readings.noteCompute(computeNanos);
        readings.noteHashPair(equal);
        if (!equal && probe.report().firstForkTick() < 0) {
            readings.noteForkUnattributed();
        }
        lastCommitted = landed ? parallelSlices : List.of();
        TaskLedger.ClosureReport closure = pass.ledger().closure(pass.dispatched(), executed,
            retried, fellback, cancelled, failed);
        pass.ledger().closeWindow();
        pass.ledger().advanceEpoch();
        return new Frame(pass.plan().tickIndex(), commitSeq, committed, redone, cancelled, failed,
            closure.ok(), parallel.value(), serial.value(), equal);
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

    /** @return merges refused because the calling thread was not the owner */
    public long foreignRuns() {
        return foreignRuns;
    }

    /** @return committed-frame hashes that no longer matched when re-taken */
    public long hashInconsistent() {
        return hashInconsistent;
    }

    /** Clears the live counters; used by the readout reset and by tests. */
    public void reset() {
        commitSeq = 0L;
        foreignRuns = 0L;
        hashInconsistent = 0L;
        lastCommitted = List.of();
    }
}
