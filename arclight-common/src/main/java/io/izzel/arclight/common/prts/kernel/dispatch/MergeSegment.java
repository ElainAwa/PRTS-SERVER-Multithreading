/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The merge: the only place a dispatched result is accepted, on the thread that owns the tick.
 *
 * <p>The walk is the frozen order of the plan. A batch whose worker answered is committed once and
 * its slot is released after its values were read; a batch that was cancelled, retried, fell back
 * or lost its worker is redone on the tick thread in the same position, so the order of the frame
 * never depends on which worker was fast enough. A second commit of one identity is refused and
 * counted.</p>
 *
 * <p>Merging also hashes the frame and compares it with the serial reference taken from the same
 * view. The comparison is a reader: it can refuse to call two arms equal, never make them equal.</p>
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
    private List<StateHasher.Slice> lastSlices = List.of();
    private long lastHash;
    private boolean lastHashTaken;

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
     * @return the frame, or {@code null} when the calling thread is not the owner
     */
    public Frame merge(DispatchPass pass, long deadlineNanos, ArenaLedger arena,
                       DispatchReadings readings, DiffProbe probe, HashWhitelist whitelist,
                       String domainId) {
        Thread owner = ownerThread;
        if (owner != null && owner != Thread.currentThread()) {
            foreignRuns++;
            return null;
        }
        // The hash of the previous frame is re-taken before the new one is built: a committed frame
        // that no longer hashes the same is a frozen scene, not a difference to explain away.
        if (lastHashTaken) {
            DomainHash again = StateHasher.hash(domainId, pass.plan().tickIndex(), lastSlices,
                whitelist);
            if (!again.comparable() || again.value() != lastHash) {
                hashInconsistent++;
                readings.noteHashInconsistent();
            }
        }
        List<TaskOutcome> outcomes = pass.awaitAll(deadlineNanos);
        List<StateHasher.Slice> parallelSlices = new ArrayList<>();
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
            boolean workerValue = outcome.status() == TaskOutcome.Status.EXECUTED
                && entry.slot() != null && entry.slot().state() == ArenaSlot.State.PUBLISHED;
            ArenaScratch scratch;
            if (workerValue) {
                scratch = entry.slot().scratch();
            } else {
                redone++;
                scratch = EntityIntegrator.mainScratch();
                try {
                    EntityIntegrator.integrateRangeSerial(entry.view(),
                        entry.batch().rangeStart(), entry.batch().rangeEnd(), scratch);
                } catch (Throwable t) {
                    if (!pass.ledger().markDropped(entry.batch().batchId(),
                        RejectCode.PROGRESS_UNOBSERVED.text())) {
                        readings.noteDroppedWithoutCode();
                    }
                    release(entry, arena);
                    continue;
                }
            }
            collect(entry, scratch, parallelSlices);
            if (!pass.ledger().markCommitted(entry.batch().batchId())) {
                readings.noteDuplicateCommit();
            } else {
                committed++;
                commitSeq++;
            }
            release(entry, arena);
        }
        Map<String, EntityCandidateView> byWorld = new LinkedHashMap<>();
        for (EntityCandidateView view : pass.plan().views()) {
            byWorld.putIfAbsent(view.worldId(), view);
        }
        List<StateHasher.Slice> serialSlices = new ArrayList<>();
        for (WorkTask task : pass.plan().tasks()) {
            EntityCandidateView view = byWorld.get(task.worldId());
            ArenaScratch scratch = EntityIntegrator.mainScratch();
            EntityIntegrator.integrateRangeSerial(view, task.entitySeqStart(), task.entitySeqEnd(),
                scratch);
            append(task, view, scratch, serialSlices);
        }
        DomainHash parallel = StateHasher.hash(domainId, pass.plan().tickIndex(), parallelSlices,
            whitelist);
        DomainHash serial = StateHasher.hash(domainId, pass.plan().tickIndex(), serialSlices,
            whitelist);
        boolean equal = parallel.comparable() && serial.comparable()
            && parallel.value() == serial.value();
        probe.compare(parallel, serial);
        readings.noteHashPair(equal);
        if (!equal && probe.report().firstForkTick() < 0) {
            readings.noteForkUnattributed();
        }
        lastSlices = parallelSlices;
        lastHash = parallel.value();
        lastHashTaken = parallel.comparable();
        TaskLedger.ClosureReport closure = pass.ledger().closure(pass.dispatched(), executed,
            retried, fellback, cancelled, failed);
        pass.ledger().closeWindow();
        pass.ledger().advanceEpoch();
        return new Frame(pass.plan().tickIndex(), commitSeq, committed, redone, cancelled, failed,
            closure.ok(), parallel.value(), serial.value(), equal);
    }

    private static void release(DispatchPass.Entry entry, ArenaLedger arena) {
        if (entry.slot() != null && entry.slot().state() != ArenaSlot.State.FREE) {
            arena.release(entry.slot());
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
        lastSlices = List.of();
        lastHash = 0L;
        lastHashTaken = false;
    }
}
