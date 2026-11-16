/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;
import io.izzel.arclight.common.prts.support.PrtsCpuClock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Domain-neutral executor: one body per JobGraph node, supplied by the owner of that domain. A
 * node is submitted only after all of its true predecessors reached their terminal point.
 *
 * <p>The executor also owns the execution end of a {@link WorkAccount}: it seats every node, moves
 * its pending sub-bucket while it is submitted and running, meters its wall and cpu time and closes
 * it when the node ends. A deadline overload checks the monotonic deadline before every submission
 * and expires what can no longer be submitted; the serial entry keeps one body in flight and walks
 * the frozen graph order. */
public final class GenericJobExecutor {

    private final WorkerPool pool;
    private final JobExecutionContext context;
    private final JobMetering metering;
    private final WorkAccount account;

    public GenericJobExecutor(WorkerPool pool) {
        this(pool, null, null, null);
    }

    public GenericJobExecutor(WorkerPool pool, JobExecutionContext context) {
        this(pool, context, null, null);
    }

    public GenericJobExecutor(WorkerPool pool, JobExecutionContext context, JobMetering metering) {
        this(pool, context, metering, null);
    }

    /** Opens an executor with an optional work account that receives the execution side of the
     * cohort: seats, pending sub-buckets, per-unit metering and terminal closures. */
    public GenericJobExecutor(WorkerPool pool, JobExecutionContext context, JobMetering metering,
                              WorkAccount account) {
        this.pool = pool;
        this.context = context;
        this.metering = metering;
        this.account = account;
    }

    /** One accepted graph execution: all node handles, including nodes not submitted yet. */
    public record Execution(ExecutionCohort cohort, List<WorkerPool.GenericHandle> handles) {
        public Execution {
            handles = List.copyOf(handles);
        }
    }

    /** Compatibility entry point: returns the handles of the accepted cohort. */
    public List<WorkerPool.GenericHandle> execute(JobGraph graph, Map<String, Runnable> bodies) {
        return executeCohort(graph, bodies).handles();
    }

    /** Compatibility entry point with a monotonic deadline; zero means no deadline. */
    public List<WorkerPool.GenericHandle> execute(JobGraph graph, Map<String, Runnable> bodies,
                                                  long deadlineNanos) {
        return executeCohort(graph, bodies, deadlineNanos).handles();
    }

    /**
     * Starts one graph execution and returns its cohort immediately. Roots are submitted here;
     * successors are submitted by their last completed predecessor, never by the caller thread.
     */
    public Execution executeCohort(JobGraph graph, Map<String, Runnable> bodies) {
        return executeCohort(graph, bodies, 0L);
    }

    /**
     * Starts one graph execution with an absolute monotonic deadline. The deadline is checked before
     * every submission of a root or successor; a node that cannot be submitted expires with the tick
     * budget code and its successors are cancelled without ever running. Zero means no deadline.
     */
    public Execution executeCohort(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos) {
        validate(graph, bodies);
        Map<String, Runnable> bodyView = Map.copyOf(bodies);
        ExecutionCohort cohort = new ExecutionCohort(graph);
        seedAccount(cohort);
        Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        for (JobGraph.Node node : graph.nodes()) {
            remaining.put(node.key(), new AtomicInteger(node.predecessors().size()));
        }
        Set<String> settledKeys = ConcurrentHashMap.newKeySet();
        for (JobGraph.Node node : graph.nodes()) {
            String key = node.key();
            cohort.handle(key).future().whenComplete((ignored, error) -> {
                settleUnit(cohort, key, settledKeys);
                if (error != null) {
                    completed(cohort, remaining, key, bodyView, deadlineNanos);
                }
            });
        }
        for (long nodeId : graph.ready()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node != null) {
                submit(cohort, remaining, node.key(), bodyView, deadlineNanos);
            }
        }
        if (graph.nodes().isEmpty() && account != null) {
            account.settle();
        }
        return new Execution(cohort, cohort.handles());
    }

    /** Compatibility entry point: walks the frozen graph order with one body in flight. */
    public Execution executeSerial(JobGraph graph, Map<String, Runnable> bodies) {
        return executeSerial(graph, bodies, 0L);
    }

    /**
     * Walks the frozen graph order with one body in flight and the same predecessor gate as the
     * parallel path. A node whose predecessor did not finish done is cancelled, and a deadline that
     * passed before its turn expires it with the tick budget code. The caller waits for each node, so
     * this entry is not meant to run on a worker thread.
     */
    public Execution executeSerial(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos) {
        validate(graph, bodies);
        Map<String, Runnable> bodyView = Map.copyOf(bodies);
        ExecutionCohort cohort = new ExecutionCohort(graph);
        seedAccount(cohort);
        Set<String> settledKeys = ConcurrentHashMap.newKeySet();
        for (JobGraph.Node node : graph.nodes()) {
            String key = node.key();
            cohort.handle(key).future().whenComplete((ignored, error) ->
                settleUnit(cohort, key, settledKeys));
        }
        for (long nodeId : graph.order()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node == null) continue;
            String key = node.key();
            if (expired(deadlineNanos)) {
                cohort.expire(key);
                settleUnit(cohort, key, settledKeys);
                continue;
            }
            if (!predecessorsDone(cohort, node)) {
                if (predecessorExpired(cohort, node)) {
                    cohort.expire(key);
                } else {
                    cohort.cancel(key);
                }
                settleUnit(cohort, key, settledKeys);
                continue;
            }
            ExecutionCohort.State state = cohort.state(key);
            if (state != ExecutionCohort.State.PENDING && state != ExecutionCohort.State.READY) {
                settleUnit(cohort, key, settledKeys);
                continue;
            }
            cohort.markReady(key);
            if (account != null) account.markReady(key);
            Runnable body = bodyView.get(key);
            if (body == null) {
                cohort.markRejected(key);
                settleUnit(cohort, key, settledKeys);
                continue;
            }
            WorkerPool.GenericHandle handle = pool.submitGeneric(
                token -> run(cohort, node, body), cohort.token(key));
            if (handle == null) {
                cohort.markRejected(key);
                settleUnit(cohort, key, settledKeys);
                continue;
            }
            cohort.markAccepted(key);
            cohort.registerWorkerHandle(key, handle);
            try {
                handle.future().join();
            } catch (CompletionException | CancellationException ignored) {
                // The node's own terminal state and account closure carry the failure.
            }
            settleUnit(cohort, key, settledKeys);
        }
        for (JobGraph.Node node : graph.nodes()) {
            if (!isTerminal(cohort.state(node.key()))) {
                cohort.cancel(node.key());
            }
            settleUnit(cohort, node.key(), settledKeys);
        }
        if (account != null) {
            account.settle();
        }
        return new Execution(cohort, cohort.handles());
    }

    private void completed(ExecutionCohort cohort, Map<String, AtomicInteger> remaining, String key,
                           Map<String, Runnable> bodies, long deadlineNanos) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        if (cohort.state(key) != ExecutionCohort.State.DONE) {
            cascade(cohort, node, key);
            return;
        }
        for (Long successor : node.successors()) {
            JobGraph.Node next = cohort.graph().node(successor);
            if (next == null) continue;
            AtomicInteger count = remaining.get(next.key());
            if (count != null && count.decrementAndGet() == 0) {
                submit(cohort, remaining, next.key(), bodies, deadlineNanos);
            }
        }
    }

    private void cascade(ExecutionCohort cohort, JobGraph.Node node, String key) {
        boolean expired = RejectCode.TICK_BUDGET_EXHAUSTED.text().equals(cohort.terminalCode(key));
        for (Long successor : node.successors()) {
            JobGraph.Node next = cohort.graph().node(successor);
            if (next == null) continue;
            if (expired) {
                cohort.expire(next.key());
            } else {
                cohort.cancel(next.key());
            }
        }
    }

    private void submit(ExecutionCohort cohort, Map<String, AtomicInteger> remaining, String key,
                        Map<String, Runnable> bodies, long deadlineNanos) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        ExecutionCohort.State state = cohort.state(key);
        if (state != ExecutionCohort.State.PENDING && state != ExecutionCohort.State.READY) return;
        if (expired(deadlineNanos)) {
            cohort.expire(key);
            return;
        }
        cohort.markReady(key);
        if (account != null) account.markReady(key);
        Runnable body = bodies.get(key);
        if (body == null) {
            cohort.markRejected(key);
            return;
        }
        WorkerPool.GenericHandle handle = pool.submitGeneric(token -> run(cohort, node, body),
            cohort.token(key), () -> completed(cohort, remaining, key, bodies, deadlineNanos));
        if (handle == null) {
            cohort.markRejected(key);
            return;
        }
        cohort.markAccepted(key);
        cohort.registerWorkerHandle(key, handle);
    }

    private void run(ExecutionCohort cohort, JobGraph.Node node, Runnable body) {
        if (!cohort.markStarted(node.key())) return;
        if (account != null) account.markRunning(node.key());
        boolean meterCpu = metering != null || account != null;
        long wallStarted = System.nanoTime();
        long cpuStarted = meterCpu ? PrtsCpuClock.now() : -1L;
        Throwable failure = null;
        try {
            body.run();
        } catch (Throwable t) {
            failure = t;
        }
        double wallMs = (System.nanoTime() - wallStarted) / 1_000_000.0;
        double cpuMs = meterCpu ? cpuMillis(cpuStarted) : 0.0;
        if (account != null) account.meter(node.key(), wallMs, cpuMs);
        boolean chargeFailed = false;
        try {
            if (context != null) chargeFailed = !context.charge(wallMs);
            if (metering != null) metering.charge(node.worldId(), node.domainId(), wallMs, cpuMs);
        } catch (Throwable t) {
            chargeFailed = true;
            if (failure == null) failure = t;
        }
        cohort.markOutcome(node.key(), failure, chargeFailed);
    }

    private void seedAccount(ExecutionCohort cohort) {
        if (account == null) return;
        JobGraph graph = cohort.graph();
        for (JobGraph.Node node : graph.nodes()) {
            account.offer(node.key(), node.key(), node.worldId(), node.domainId());
            account.seat(node.key(), graph.planSequence(), graph.tickIndex(), node.position());
        }
    }

    private void settleUnit(ExecutionCohort cohort, String key, Set<String> settledKeys) {
        if (account == null || key == null || !settledKeys.add(key)) return;
        WorkAccount.Outcome outcome = outcomeOf(cohort.state(key));
        if (outcome == null) {
            settledKeys.remove(key);
            return;
        }
        account.close(key, outcome, cohort.terminalCode(key));
        if (settledKeys.size() == cohort.graph().nodeCount()) {
            account.settle();
        }
    }

    private static WorkAccount.Outcome outcomeOf(ExecutionCohort.State state) {
        if (state == null) return null;
        return switch (state) {
            case DONE -> WorkAccount.Outcome.DONE;
            case FAILED -> WorkAccount.Outcome.FAULT;
            case FAILED_AFTER_EFFECT -> WorkAccount.Outcome.PARTIAL_FAULT;
            case CANCELLED -> WorkAccount.Outcome.CANCELLED_NO_EFFECT;
            case CANCELLED_AFTER_EFFECT -> WorkAccount.Outcome.PARTIAL_FAULT;
            case REJECTED -> WorkAccount.Outcome.REJECTED_BEFORE_EFFECT;
            case PENDING, READY, RUNNING -> null;
        };
    }

    private static boolean predecessorsDone(ExecutionCohort cohort, JobGraph.Node node) {
        for (String predecessor : node.predecessors()) {
            if (cohort.state(predecessor) != ExecutionCohort.State.DONE) {
                return false;
            }
        }
        return true;
    }

    private static boolean predecessorExpired(ExecutionCohort cohort, JobGraph.Node node) {
        for (String predecessor : node.predecessors()) {
            if (RejectCode.TICK_BUDGET_EXHAUSTED.text().equals(cohort.terminalCode(predecessor))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTerminal(ExecutionCohort.State state) {
        return state == ExecutionCohort.State.DONE || state == ExecutionCohort.State.FAILED
            || state == ExecutionCohort.State.FAILED_AFTER_EFFECT
            || state == ExecutionCohort.State.CANCELLED
            || state == ExecutionCohort.State.CANCELLED_AFTER_EFFECT
            || state == ExecutionCohort.State.REJECTED;
    }

    private static boolean expired(long deadlineNanos) {
        return deadlineNanos != 0L && System.nanoTime() - deadlineNanos >= 0L;
    }

    private static double cpuMillis(long cpuStartedNanos) {
        long used = PrtsCpuClock.since(cpuStartedNanos);
        return used < 0L ? 0.0 : used / 1_000_000.0;
    }

    private void validate(JobGraph graph, Map<String, Runnable> bodies) {
        if (graph == null) throw new IllegalArgumentException("graph");
        if (bodies == null) throw new IllegalArgumentException("bodies");
        for (JobGraph.Node node : graph.nodes()) {
            if (!bodies.containsKey(node.key())) {
                throw new RejectedExecutionException("no body for job " + node.key());
            }
            if (context != null) validate(node);
        }
        if (context != null && context.exhausted()) {
            throw new RejectedExecutionException("share budget exhausted");
        }
    }

    private void validate(JobGraph.Node node) {
        for (JobDeclaration.DomainRef ref : node.readSet()) {
            if (!context.allowsRead(ref.domainId())) {
                throw new RejectedExecutionException("read not declared: " + ref.key());
            }
        }
        for (JobDeclaration.DomainRef ref : node.writeSet()) {
            if (!context.allowsWrite(ref.domainId())) {
                throw new RejectedExecutionException("write not declared: " + ref.key());
            }
        }
        for (JobDeclaration.OwnerDemand demand : node.ownerDemands()) {
            if (!context.allowsOwner(demand.domainId())) {
                throw new RejectedExecutionException("owner not declared: " + demand.domainId());
            }
        }
    }
}
