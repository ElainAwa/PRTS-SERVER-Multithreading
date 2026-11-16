/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskOutcome;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool.WorkerHandle;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
import io.izzel.arclight.common.prts.kernel.generic.ExecutionCohort;
import io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs one frozen plan as one execution cohort: every task becomes a node of a job graph, the node
 * body runs the domain's work body on a shared worker, publishes the arena slot it wrote and leaves
 * the lease to the merge; a body that cannot publish releases its lease itself. The cohort settles
 * each node - done, cancelled, expired or charge-failed - and the executor carrying it closes the
 * matching unit of the work account.
 *
 * <p>The returned pass carries the entry shape the merge and the write back already read, so neither
 * has to know which dispatcher produced it. A task whose slot could not be claimed is carried as an
 * immediate fallback outcome and takes no node, and a timeout of the merge is forwarded to the cohort
 * before the batch token is cancelled.
 */
public final class CohortDispatchPass {

    private static final String DOMAIN = DispatchWriteBack.commitDomain();
    private static final int LEVEL = 0;
    private static final int BATCH_KIND = 0;

    private CohortDispatchPass() {
    }

    /** The execution side of one cohort dispatch: the pool its bodies run on, the executor that
     * seats and closes the account units, the arena the slots are claimed from and the books of the
     * pass. The caller owns all five; this class only uses them. */
    public record ExecutionSide(WorkerPool pool, GenericJobExecutor executor, ArenaLedger arena,
                                DispatchReadings readings, TaskLedger ledger) {
    }

    /** Dispatches one frozen plan as a cohort; the planned graph, when given, supplies the true
     * predecessors a task still has. */
    public static DispatchPass dispatch(WorkPlan plan, ExecutionSide side, WorkBody body,
                                        List<WorkTask> orderedTasks, JobGraph planned) {
        return dispatch(plan, side, body, orderedTasks, planned, null);
    }

    /**
     * Dispatches one frozen plan as a cohort and hands the observer the cohort after the account was
     * seeded and before any node can run. Tasks are taken in the given order; a task whose arena slot
     * is refused receives its terminal outcome at once and joins no node.
     */
    public static DispatchPass dispatch(WorkPlan plan, ExecutionSide side, WorkBody body,
                                        List<WorkTask> orderedTasks, JobGraph planned,
                                        Consumer<ExecutionCohort> observer) {
        Map<String, EntityCandidateView> byWorld = new LinkedHashMap<>();
        for (EntityCandidateView view : plan.views()) {
            byWorld.putIfAbsent(view.worldId(), view);
        }
        List<DispatchPass.Entry> entries = new ArrayList<>(orderedTasks.size());
        List<Unit> units = new ArrayList<>(orderedTasks.size());
        Map<String, Unit> byKey = new LinkedHashMap<>();
        Map<String, Unit> byBaseKey = new LinkedHashMap<>();
        for (WorkTask task : orderedTasks) {
            EntityCandidateView view = byWorld.get(task.worldId());
            WorkBatch batch = new WorkBatch(task.batchId(), task, plan.planEpoch(), view);
            side.ledger().register(task.batchId());
            CancelToken token = new CancelToken(plan.planEpoch());
            ArenaSlot slot = side.arena().claim(task.batchId(), task.worldId(), task.regionId(),
                EntityIntegrator.SEGMENT_KIND, task.entityCount());
            side.readings().noteDispatched();
            if (slot == null) {
                side.readings().noteBackpressure();
                entries.add(new DispatchPass.Entry(batch, view, null, token, null, null,
                    TaskOutcome.fellback(task.batchId(), 0, Thread.currentThread(),
                        RejectCode.QUEUE_CAP_EXCEEDED.text())));
                continue;
            }
            ArenaSlot.Lease lease = slot.lease(plan.planEpoch());
            String key = keyOf(task, plan.tickIndex(), byKey);
            String baseKey = DispatchWriteBack.nodeKey(task);
            Unit unit = new Unit(key, baseKey, batch, body, token, slot, lease, side);
            units.add(unit);
            byKey.put(key, unit);
            byBaseKey.put(baseKey, unit);
            entries.add(new DispatchPass.Entry(batch, view, unit.handle(), token, slot, lease, null));
        }
        if (units.isEmpty()) {
            side.readings().noteQueueDepth(side.pool().queueDepth());
            return DispatchPass.adopt(plan, entries, side.readings(), side.ledger());
        }
        JobGraph graph = graph(plan, units, byBaseKey, planned);
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        for (Unit unit : units) {
            bodies.put(unit.key(), unit::run);
        }
        Consumer<ExecutionCohort> installer = cohort -> {
            for (Unit unit : units) {
                unit.attach(cohort);
            }
            cohort.onSettled(settlement -> {
                Unit unit = byKey.get(settlement.key());
                TaskOutcome outcome = unit == null ? null : unit.outcome(settlement);
                if (outcome != null) {
                    unit.complete(outcome);
                }
            });
            if (observer != null) {
                observer.accept(cohort);
            }
        };
        GenericJobExecutor.Execution execution;
        try {
            execution = side.executor().executeCohort(graph, bodies, 0L, installer);
        } catch (Throwable failure) {
            return abort(plan, entries, side);
        }
        DispatchPass pass = DispatchPass.adopt(plan, entries, side.readings(), side.ledger());
        ExecutionCohort cohort = execution.cohort();
        for (Unit unit : units) {
            pass.bindTimeout(unit.batch().batchId(), () -> cohort.expire(unit.key()));
        }
        side.readings().noteQueueDepth(side.pool().queueDepth());
        return pass;
    }

    /** Closes a pass the executor refused to start: no body runs, so every claimed lease is returned
     * and every batch carries a terminal fallback the merge can close. */
    private static DispatchPass abort(WorkPlan plan, List<DispatchPass.Entry> entries,
                                      ExecutionSide side) {
        List<DispatchPass.Entry> closed = new ArrayList<>(entries.size());
        for (DispatchPass.Entry entry : entries) {
            if (entry.lease() != null) {
                side.arena().release(entry.lease(), true);
            }
            if (entry.immediate() != null) {
                closed.add(entry);
                continue;
            }
            closed.add(new DispatchPass.Entry(entry.batch(), entry.view(), null, entry.token(), null,
                null, TaskOutcome.fellback(entry.batch().batchId(), 0, Thread.currentThread(),
                    RejectCode.QUEUE_CAP_EXCEEDED.text())));
        }
        side.readings().noteBackpressure();
        return DispatchPass.adopt(plan, closed, side.readings(), side.ledger());
    }

    /** The unit identity of one task of one logical update: the stable node key plus the plan tick,
     * so the same node of two ticks is two units of the work account. */
    public static String unitIdOf(WorkPlan plan, WorkTask task) {
        return WorkAccount.unitId(DispatchWriteBack.nodeKey(task), plan.tickIndex());
    }

    private static String keyOf(WorkTask task, long tickIndex, Map<String, Unit> taken) {
        String key = WorkAccount.unitId(DispatchWriteBack.nodeKey(task), tickIndex);
        if (!taken.containsKey(key)) {
            return key;
        }
        // Two tasks of one world and region are not produced by the freeze; the suffix only keeps a
        // hand-built plan from collapsing two nodes into one key.
        return key + "#" + task.taskId();
    }

    /** The execution graph of one dispatch: the frozen order is the position order, and the
     * predecessors are the planned graph's edges whose two ends are both in this task set. */
    private static JobGraph graph(WorkPlan plan, List<Unit> units, Map<String, Unit> byBaseKey,
                                  JobGraph planned) {
        Map<String, Long> ids = new LinkedHashMap<>();
        long nextId = 1L;
        for (Unit unit : units) {
            ids.put(unit.key(), nextId++);
        }
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        Map<String, List<Long>> successors = new LinkedHashMap<>();
        for (String key : ids.keySet()) {
            successors.put(key, new ArrayList<>());
        }
        for (Unit unit : units) {
            String key = unit.key();
            List<String> kept = new ArrayList<>();
            // The planned graph is keyed by the stable node key: one node of two ticks is one plan
            // node, so its edges resolve to this tick's unit identity here.
            JobGraph.Node node = planned == null ? null : planned.nodeByKey(unit.baseKey());
            if (node != null) {
                for (String predecessor : node.predecessors()) {
                    Unit predecessorUnit = byBaseKey.get(predecessor);
                    if (predecessorUnit != null && !predecessorUnit.key().equals(key)) {
                        kept.add(predecessorUnit.key());
                    }
                }
            }
            predecessors.put(key, List.copyOf(kept));
        }
        for (Map.Entry<String, List<String>> edges : predecessors.entrySet()) {
            for (String predecessor : edges.getValue()) {
                successors.get(predecessor).add(ids.get(edges.getKey()));
            }
        }
        List<JobGraph.Node> nodes = new ArrayList<>(units.size());
        List<Long> order = new ArrayList<>(units.size());
        List<Long> ready = new ArrayList<>();
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        int position = 0;
        for (Unit unit : units) {
            String key = unit.key();
            long nodeId = ids.get(key);
            JobGraph.Node node = node(nodeId, position, unit, predecessors.get(key),
                successors.get(key));
            nodes.add(node);
            order.add(nodeId);
            if (node.root()) {
                ready.add(nodeId);
            }
            groups.computeIfAbsent(node.affinity(), ignored -> new ArrayList<>()).add(nodeId);
            position++;
        }
        List<JobGraph.AffinityGroup> affinity = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<Long>> group : groups.entrySet()) {
            affinity.add(new JobGraph.AffinityGroup(group.getKey(), group.getValue()));
        }
        return new JobGraph(plan.tickIndex(), plan.planEpoch(), 0L, nodes, order, ready, affinity,
            Math.max(1, units.size()), 0);
    }

    private static JobGraph.Node node(long nodeId, int position, Unit unit,
                                      List<String> predecessors, List<Long> successors) {
        WorkTask task = unit.batch().task();
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(task.worldId(), DOMAIN, LEVEL);
        return new JobGraph.Node(nodeId, unit.key(), task.batchId(), task.worldId(), DOMAIN, LEVEL,
            predecessors, successors, task.entityCount(),
            task.worldId() + "/" + DOMAIN + "/" + task.regionId(), task.cancelScope(), List.of(ref),
            List.of(ref), List.of(), ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, BATCH_KIND,
            Math.max(1, task.entityCount()), false, position);
    }

    private enum Fault {
        NONE,
        RETRYABLE,
        FELLBACK,
        HARD
    }

    /** One dispatched task: its batch, its lease, the handle the merge waits on and what the body
     * learned while it ran. */
    private static final class Unit {

        private final String key;
        private final String baseKey;
        private final WorkBatch batch;
        private final WorkBody body;
        private final CancelToken token;
        private final ArenaSlot slot;
        private final ArenaSlot.Lease lease;
        private final ExecutionSide side;
        private final WorkerHandle handle;
        private volatile ExecutionCohort cohort;
        private volatile Thread execThread;
        private volatile Fault fault = Fault.NONE;
        private volatile String code = "";
        private volatile long checksum;

        private Unit(String key, String baseKey, WorkBatch batch, WorkBody body, CancelToken token,
                     ArenaSlot slot, ArenaSlot.Lease lease, ExecutionSide side) {
            this.key = key;
            this.baseKey = baseKey;
            this.batch = batch;
            this.body = body;
            this.token = token;
            this.slot = slot;
            this.lease = lease;
            this.side = side;
            this.handle = new WorkerHandle(batch.batchId(), batch.batchEpoch());
        }

        private String key() {
            return key;
        }

        private String baseKey() {
            return baseKey;
        }

        private WorkBatch batch() {
            return batch;
        }

        private WorkerHandle handle() {
            return handle;
        }

        private void attach(ExecutionCohort cohort) {
            this.cohort = cohort;
        }

        private void complete(TaskOutcome outcome) {
            handle.complete(outcome);
        }

        private void run() {
            Thread worker = Thread.currentThread();
            execThread = worker;
            long started = System.nanoTime();
            boolean published = false;
            try {
                if (token.cancelled()) {
                    cancelWithinCohort();
                    return;
                }
                checksum = body.run(batch, lease.scratch(), token);
                if (token.cancelled()) {
                    cancelWithinCohort();
                    return;
                }
                published = slot.publish(lease);
                if (!published) {
                    fault = Fault.FELLBACK;
                    code = RejectCode.WRITE_DENIED_NOT_OWNER.text();
                }
            } catch (WorkBody.CancelledFault cancelled) {
                cancelWithinCohort();
            } catch (WorkBody.RetryableFault retryable) {
                fault = Fault.RETRYABLE;
                code = RejectCode.VERSION_MISMATCH.text();
            } catch (WorkBody.NonRetryableFault fellBack) {
                fault = Fault.FELLBACK;
                code = RejectCode.WRITE_DENIED_NOT_OWNER.text();
            } catch (RuntimeException | Error hard) {
                fault = Fault.HARD;
            } finally {
                side.readings().noteExecution(worker.getName(), System.nanoTime() - started);
                if (!published) {
                    side.arena().release(lease, true);
                }
            }
        }

        /** A cancellation is a request on the node, not a fault: a body that stopped because its
         * token was set must settle cancelled, so the node is marked before the outcome is read. */
        private void cancelWithinCohort() {
            ExecutionCohort current = cohort;
            if (current != null) {
                current.cancel(key);
            }
        }

        private TaskOutcome outcome(ExecutionCohort.Settlement settlement) {
            Thread thread = execThread == null ? Thread.currentThread() : execThread;
            int attempts = execThread == null ? 0 : 1;
            long batchId = batch.batchId();
            String terminal = code.isEmpty() ? settlement.terminalCode() : code;
            return switch (settlement.state()) {
                case DONE -> TaskOutcome.executed(batchId, attempts, thread, slot.ref(), checksum);
                case CANCELLED, CANCELLED_AFTER_EFFECT -> TaskOutcome.cancelled(batchId, attempts,
                    thread.threadId(), thread.getName(), settlement.terminalCode());
                case FAILED, FAILED_AFTER_EFFECT -> switch (fault) {
                    case RETRYABLE -> TaskOutcome.retried(batchId, attempts, thread, terminal);
                    case FELLBACK -> TaskOutcome.fellback(batchId, attempts, thread, terminal);
                    case NONE, HARD -> TaskOutcome.failed(batchId, attempts, thread, terminal);
                };
                case REJECTED -> {
                    side.readings().noteBackpressure();
                    yield TaskOutcome.fellback(batchId, 0, thread, settlement.terminalCode());
                }
                case PENDING, READY, RUNNING -> null;
            };
        }
    }
}
