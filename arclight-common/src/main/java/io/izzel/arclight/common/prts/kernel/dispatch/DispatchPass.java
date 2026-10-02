/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tick's dispatch: every frozen task offered to the pool, with its token, its slot and its
 * handle. A task the pool refused carries an immediate outcome instead of being lost, and a pass the
 * switch ends before it is merged is closed by {@link #abort(String)} with a terminal state per
 * batch.
 */
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

    /**
     * Freezes a plan the tick thread runs itself because no pool could take it.
     *
     * <p>Every batch receives a terminal outcome here, so the accounting closes even when the pool
     * never started; no slot is claimed.</p>
     */
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

    /**
     * Waits until the deadline, cancelling what did not answer; a late result is refused by the
     * handle and counted.
     */
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

    /**
     * Ends a pass that will never be merged: every pending batch is cancelled and dropped with
     * {@code code}, then the window is cleared and the epoch advances.
     */
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
}
