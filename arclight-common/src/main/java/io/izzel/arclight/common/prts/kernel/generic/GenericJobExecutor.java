/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** Domain-neutral executor: one body per JobGraph node, supplied by the owner of that domain. A
 * node is submitted only after all of its true predecessors reached their terminal point. */
public final class GenericJobExecutor {

    private final WorkerPool pool;
    private final JobExecutionContext context;
    private final JobMetering metering;

    public GenericJobExecutor(WorkerPool pool) {
        this(pool, null, null);
    }

    public GenericJobExecutor(WorkerPool pool, JobExecutionContext context) {
        this(pool, context, null);
    }

    public GenericJobExecutor(WorkerPool pool, JobExecutionContext context, JobMetering metering) {
        this.pool = pool;
        this.context = context;
        this.metering = metering;
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

    /**
     * Starts one graph execution and returns its cohort immediately. Roots are submitted here;
     * successors are submitted by their last completed predecessor, never by the caller thread.
     */
    public Execution executeCohort(JobGraph graph, Map<String, Runnable> bodies) {
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
        ExecutionCohort cohort = new ExecutionCohort(graph);
        Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        for (JobGraph.Node node : graph.nodes()) {
            remaining.put(node.key(), new AtomicInteger(node.predecessors().size()));
        }
        Map<String, Runnable> bodyView = Map.copyOf(bodies);
        for (JobGraph.Node node : graph.nodes()) {
            cohort.handle(node.key()).future().whenComplete((ignored, error) ->
                completed(cohort, remaining, node.key(), error, bodyView));
        }
        for (long nodeId : graph.ready()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node != null) submit(cohort, remaining, node.key(), bodyView);
        }
        return new Execution(cohort, cohort.handles());
    }

    private void completed(ExecutionCohort cohort, Map<String, AtomicInteger> remaining, String key,
                           Throwable error, Map<String, Runnable> bodies) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        if (error != null) {
            for (Long successor : node.successors()) {
                JobGraph.Node next = cohort.graph().node(successor);
                if (next != null) cohort.cancel(next.key());
            }
            return;
        }
        for (Long successor : node.successors()) {
            JobGraph.Node next = cohort.graph().node(successor);
            if (next == null) continue;
            AtomicInteger count = remaining.get(next.key());
            if (count != null && count.decrementAndGet() == 0) {
                submit(cohort, remaining, next.key(), bodies);
            }
        }
    }

    private void submit(ExecutionCohort cohort, Map<String, AtomicInteger> remaining, String key,
                        Map<String, Runnable> bodies) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        ExecutionCohort.State state = cohort.state(key);
        if (state != ExecutionCohort.State.PENDING && state != ExecutionCohort.State.READY) return;
        cohort.markReady(key);
        Runnable body = bodies.get(key);
        if (body == null) {
            cohort.markRejected(key);
            return;
        }
        WorkerPool.GenericHandle handle = pool.submitGeneric(token -> run(cohort, node, body),
            cohort.token(key));
        if (handle == null) {
            cohort.markRejected(key);
            return;
        }
        cohort.markAccepted(key);
        cohort.registerWorkerHandle(key, handle);
        cohort.markRunning(key);
    }

    private void run(ExecutionCohort cohort, JobGraph.Node node, Runnable body) {
        cohort.markRunning(node.key());
        long started = System.nanoTime();
        try {
            if (cohort.token(node.key()).cancelled()) {
                cohort.cancel(node.key());
                return;
            }
            body.run();
            double millis = (System.nanoTime() - started) / 1_000_000.0;
            if (context != null && !context.charge(millis)) {
                cohort.markFailedAfterEffect(node.key(),
                    new RejectedExecutionException("share budget exhausted"));
                return;
            }
            if (metering != null) {
                metering.charge(node.worldId(), node.domainId(), millis);
            }
            cohort.markDone(node.key());
        } catch (Throwable t) {
            cohort.markFailed(node.key(), t);
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
