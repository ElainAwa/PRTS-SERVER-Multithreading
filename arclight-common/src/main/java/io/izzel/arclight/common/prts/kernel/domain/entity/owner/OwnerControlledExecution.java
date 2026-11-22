/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.generic.ExecutionCohort;
import io.izzel.arclight.common.prts.kernel.generic.GenericCancel;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One begun controlled write whose body runs on a worker: the persistent record that owns the
 * ticket, the real body result and the owner hand-off until the terminal is written. A worker
 * future being done only means the body node ended; this record stays responsible for the owner
 * finish or fault and for the save/stop fence. */
final class OwnerControlledExecution {

    /** The stages a controlled body passes; only OWNER_DONE and ABANDONED end the responsibility. */
    enum Stage {
        BEGUN,
        BODY_RUNNING,
        EFFECT_ENTERED,
        BODY_RETURNED,
        OWNER_DONE,
        ABANDONED
    }

    /** The real body's monotone effect witness, written by the worker immediately before the first
     * controlled write and never by a default token method. */
    static final class EffectWitness implements GenericCancel {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean effectPossible = new AtomicBoolean();

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public void markEffectPossible() {
            effectPossible.set(true);
        }

        @Override
        public boolean effectPossible() {
            return effectPossible.get();
        }
    }

    private static final Set<OwnerControlledExecution> PENDING = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<OwnerControlledExecution> WAITING = new ThreadLocal<>();

    final ServerThreadOwnerPhase.DeferredRow row;
    final OwnerControlledWrite.Admission admission;
    final CommitTicket ticket;
    final EffectWitness witness = new EffectWitness();
    final AtomicBoolean lateScheduled = new AtomicBoolean();

    volatile Stage stage = Stage.BEGUN;
    volatile boolean ownerWaiting;
    volatile CommitTicket.Outcome outcome;
    volatile String effectRow;
    volatile Throwable bodyFailure;
    volatile Throwable cleanupFailure;
    volatile ExecutionCohort cohort;
    volatile WorkerPool.GenericHandle handle;

    OwnerControlledExecution(ServerThreadOwnerPhase.DeferredRow row,
                             OwnerControlledWrite.Admission admission, CommitTicket ticket) {
        this.row = row;
        this.admission = admission;
        this.ticket = ticket;
    }

    static void register(OwnerControlledExecution record) {
        PENDING.add(record);
    }

    static void retire(OwnerControlledExecution record) {
        PENDING.remove(record);
    }

    static boolean anyPending() {
        return !PENDING.isEmpty();
    }

    static int pendingCount() {
        return PENDING.size();
    }

    static void waiting(OwnerControlledExecution record) {
        record.ownerWaiting = true;
        WAITING.set(record);
    }

    static void doneWaiting() {
        WAITING.remove();
    }

    void endOwnerWait() {
        ownerWaiting = false;
    }

    static boolean waitingOnThisThread() {
        return WAITING.get() != null;
    }

    CommitController controller() {
        return admission.controller();
    }
}
