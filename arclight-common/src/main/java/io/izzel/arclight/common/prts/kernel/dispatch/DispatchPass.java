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
 * handle.
 *
 * <p>The pass is what the merge reads afterwards. A task the pool refused because the queue was
 * full is not lost: it carries an immediate outcome and the tick thread redoes it, which is the
 * documented fallback rather than a silent drop.</p>
 */
public final class DispatchPass {

    /**
     * One dispatched batch and everything the merge needs to judge it.
     *
     * @param batch          the frozen batch
     * @param view           the view the batch was cut from
     * @param handle         the answer slot, or {@code null} when the pool refused the batch
     * @param token          the cancellation token
     * @param slot           the arena slot the body writes
     * @param slotGeneration the generation the slot was claimed at
     * @param immediate      the outcome of a batch the pool never ran
     */
    public record Entry(WorkBatch batch, EntityCandidateView view, WorkerHandle handle,
                        CancelToken token, ArenaSlot slot, long slotGeneration,
                        TaskOutcome immediate) {
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

    /**
     * Dispatches a frozen plan.
     *
     * @param plan     the plan to dispatch
     * @param pool     the pool to offer the batches to
     * @param body     the body the workers run
     * @param arena    the arena the slots come from
     * @param readings where the pass publishes
     * @param ledger   the batch ledger
     * @return the pass the merge reads
     */
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
                    0L, TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                    RejectCode.QUEUE_CAP_EXCEEDED.text())));
                continue;
            }
            CancelToken token = new CancelToken(plan.planEpoch());
            long generation = slot.ref().slotGeneration();
            WorkerHandle handle = pool.submit(batch, token, body, slot, generation);
            readings.noteDispatched();
            if (handle == null) {
                readings.noteBackpressure();
                arena.release(slot);
                entries.add(new Entry(batch, view, null, token, null, 0L,
                    TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                        RejectCode.QUEUE_CAP_EXCEEDED.text())));
            } else {
                entries.add(new Entry(batch, view, handle, token, slot, generation, null));
            }
        }
        readings.noteQueueDepth(pool.queueDepth());
        return new DispatchPass(plan, entries, readings, ledger);
    }

    /**
     * Waits for every batch until the deadline, cancelling what did not answer.
     *
     * <p>A batch that did not answer is not dropped: the pass publishes a cancellation outcome so
     * the merge redoes it on the tick thread, and the worker that answers afterwards is refused by
     * the handle and counted as late.</p>
     *
     * @param deadlineNanos the monotonic instant the wait ends
     * @return one terminal outcome per batch, in the frozen order
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

    /** @return the plan this pass dispatched */
    public WorkPlan plan() {
        return plan;
    }

    /** @return the dispatched entries, in the frozen order */
    public List<Entry> entries() {
        return entries;
    }

    /** @return the batches the tick dispatched */
    public int dispatched() {
        return entries.size();
    }

    /** @return the ledger the pass registered its batches with */
    public TaskLedger ledger() {
        return ledger;
    }
}