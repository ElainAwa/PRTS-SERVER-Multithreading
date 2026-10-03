/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool.WorkerHandle;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One tick's dispatch: every frozen task offered to the pool, with its token, its slot and its
 * handle. */
public final class DispatchPass {

    /** One dispatched batch: the slot and lease are {@code null} when the pool refused it. */
    public record Entry(WorkBatch batch, EntityCandidateView view, WorkerHandle handle,
                        CancelToken token, ArenaSlot slot, ArenaSlot.Lease lease,
                        TaskOutcome immediate) {
    }

    /** What closing an unmerged pass did; {@code closed} says no batch of it is pending. */
    public record AbortReport(int cancelled, int dropped, int terminal, boolean closed) {
    }

    private final WorkPlan plan;
    private final List<Entry> entries;
    private final DispatchReadings readings;
    private final TaskLedger ledger;

    private DispatchPass(WorkPlan plan, List<Entry> entries, DispatchReadings readings,
                         TaskLedger ledger) {
        this.plan = plan;
        this.entries = List.copyOf(entries);
        this.readings = readings;
        this.ledger = ledger;
    }

    /** Dispatches a frozen plan and returns the pass the merge reads. */
    public static DispatchPass dispatch(WorkPlan plan, WorkerPool pool, WorkBody body,
                                        ArenaLedger arena, DispatchReadings readings,
                                        TaskLedger ledger) {
        Map<String, EntityCandidateView> byWorld = new LinkedHashMap<>();
        for (EntityCandidateView view : plan.views()) {
            byWorld.putIfAbsent(view.worldId(), view);
        }
        List<Entry> entries = new ArrayList<>(plan.taskCount());
        for (WorkTask task : plan.tasks()) {
            EntityCandidateView view = byWorld.get(task.worldId());
            WorkBatch batch = new WorkBatch(task.batchId(), task, plan.planEpoch(), view);
            ledger.register(task.batchId());
            ArenaSlot slot = arena.claim(task.batchId(), task.worldId(), task.regionId(),
                EntityIntegrator.SEGMENT_KIND, task.entityCount());
            if (slot == null) {
                readings.noteBackpressure();
                readings.noteDispatched();
                entries.add(new Entry(batch, view, null, new CancelToken(plan.planEpoch()), null,
                    null, TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                    RejectCode.QUEUE_CAP_EXCEEDED.text())));
                continue;
            }
            CancelToken token = new CancelToken(plan.planEpoch());
            ArenaSlot.Lease lease = slot.lease(plan.planEpoch());
            WorkerHandle handle = pool.submit(batch, token, body, slot, lease);
            readings.noteDispatched();
            if (handle == null) {
                readings.noteBackpressure();
                arena.release(lease, true);
                entries.add(new Entry(batch, view, null, token, null, null,
                    TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                        RejectCode.QUEUE_CAP_EXCEEDED.text())));
            } else {
                entries.add(new Entry(batch, view, handle, token, slot, lease, null));
            }
        }
        readings.noteQueueDepth(pool.queueDepth());
        return new DispatchPass(plan, entries, readings, ledger);
    }

    /** Every batch receives a terminal outcome here, so the accounting closes even when the pool
     * never started; no slot is claimed. */
    public static DispatchPass serialFallback(WorkPlan plan, DispatchReadings readings,
                                              TaskLedger ledger) {
        Map<String, EntityCandidateView> byWorld = new LinkedHashMap<>();
        for (EntityCandidateView view : plan.views()) {
            byWorld.putIfAbsent(view.worldId(), view);
        }
        List<Entry> entries = new ArrayList<>(plan.taskCount());
        for (WorkTask task : plan.tasks()) {
            EntityCandidateView view = byWorld.get(task.worldId());
            WorkBatch batch = new WorkBatch(task.batchId(), task, plan.planEpoch(), view);
            ledger.register(task.batchId());
            readings.noteDispatched();
            readings.noteBackpressure();
            entries.add(new Entry(batch, view, null, new CancelToken(plan.planEpoch()), null, null,
                TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                    RejectCode.QUEUE_CAP_EXCEEDED.text())));
        }
        return new DispatchPass(plan, entries, readings, ledger);
    }

    /** Waits until the deadline, cancelling what did not answer; a late result is refused by the
     * handle and counted. */
    public List<TaskOutcome> awaitAll(long deadlineNanos) {
        List<TaskOutcome> outcomes = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            TaskOutcome outcome = entry.immediate();
            if (outcome == null) {
                long remaining = deadlineNanos - System.nanoTime();
                outcome = entry.handle().await(remaining);
            }
            if (outcome == null) {
                TaskOutcome cancelledOutcome = TaskOutcome.cancelled(entry.batch().batchId(), 0,
                    Thread.currentThread().threadId(), Thread.currentThread().getName(),
                    RejectCode.TICK_BUDGET_EXHAUSTED.text());
                entry.token().cancel();
                if (entry.handle().complete(cancelledOutcome)) {
                    readings.noteTimeout();
                    outcome = cancelledOutcome;
                } else {
                    // The worker answered in the same instant the deadline passed; its result is
                    // accepted instead of being overwritten by the cancellation.
                    outcome = entry.handle().peek() == null ? cancelledOutcome
                        : entry.handle().peek();
                }
            }
            readings.noteOutcome(outcome);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    /** Ends a pass that will never be merged: every pending batch is cancelled and dropped with
     * {@code code}, then the window is cleared and the epoch advances. */
    public AbortReport abort(String code) {
        int cancelled = 0;
        int dropped = 0;
        int terminal = 0;
        for (Entry entry : entries) {
            long batchId = entry.batch().batchId();
            if (ledger.state(batchId) != TaskLedger.BatchState.PENDING) {
                terminal++;
                continue;
            }
            entry.token().cancel();
            if (entry.handle() != null && !entry.handle().isDone()) {
                TaskOutcome cancelledOutcome = TaskOutcome.cancelled(batchId, 0,
                    Thread.currentThread().threadId(), Thread.currentThread().getName(), code);
                if (entry.handle().complete(cancelledOutcome)) {
                    cancelled++;
                }
            }
            ledger.markDropped(batchId, code);
            readings.noteShutdownDropped();
            dropped++;
        }
        ledger.closeWindow();
        ledger.advanceEpoch();
        return new AbortReport(cancelled, dropped, terminal,
            ledger.pendingCount() == 0L);
    }

    public WorkPlan plan() {
        return plan;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int dispatched() {
        return entries.size();
    }

    public TaskLedger ledger() {
        return ledger;
    }

    /** The declared names and defaults live in the configuration layer; this class turns them into one
     * bounded policy at the moment a tick needs it, so a value is never used unclamped. */
    public static final class DispatchSettings {

        /** Upper bound of the derived worker count. */
        public static final int DERIVED_WORKER_CAP = 4;

        /** Upper bound an operator may declare for the worker count. */
        public static final int DECLARED_WORKER_CAP = 8;

        /** Upper bound of the queue depth. */
        public static final int QUEUE_CAP_MAX = 256;

        /** Upper bound of the region size in chunks. */
        public static final int BATCH_CHUNKS_MAX = 64;

        /** Upper bound of the deadline grace in milliseconds. */
        public static final int DEADLINE_GRACE_MS_MAX = 1000;

        /** Upper bound of the worker retry budget. */
        public static final int RETRY_BUDGET_MAX = 2;

        private DispatchSettings() {
        }

        /** The bounded policy one tick runs with. */
        public record Policy(int workerCount, int queueCap, int batchChunks, int deadlineGraceMs,
                             int retryBudget) {
        }

        /** Derives the worker count of a machine. */
        public static int derivedWorkerCount(int cores) {
            return Math.max(1, Math.min(DERIVED_WORKER_CAP, cores - 1));
        }

        /** Resolves the declared worker count, falling back to the derived form. */
        public static int workerCount(int declared, int cores) {
            if (declared <= 0) {
                return derivedWorkerCount(cores);
            }
            return Math.min(DECLARED_WORKER_CAP, Math.max(1, declared));
        }

        /** Resolves the policy of this process. */
        public static Policy resolve() {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            int workers = workerCount(KernelSettings.workerCountDeclared(), cores);
            int queueCap = KernelSettings.clamp(KernelSettings.workerQueueCap(), 1, QUEUE_CAP_MAX);
            int chunks = KernelSettings.clamp(KernelSettings.workerBatchChunks(), 1, BATCH_CHUNKS_MAX);
            int grace = KernelSettings.clamp(KernelSettings.workerDeadlineGraceMs(), 0,
                DEADLINE_GRACE_MS_MAX);
            // The worker budget may never exceed the retry budget the intent channel already uses.
            int retry = Math.min(KernelSettings.clamp(KernelSettings.workerRetryBudget(), 0,
                RETRY_BUDGET_MAX), KernelSettings.retryBudget());
            return new Policy(workers, queueCap, chunks, grace, retry);
        }
    }
}
