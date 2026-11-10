/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/** Domain-neutral executor: one body per JobGraph node, supplied by the owner of that domain. */
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

    public List<WorkerPool.GenericHandle> execute(JobGraph graph, Map<String, Runnable> bodies) {
        List<WorkerPool.GenericHandle> handles = new ArrayList<>();
        java.util.Map<Long, JobGraph.Node> byId = new java.util.HashMap<>();
        for (JobGraph.Node node : graph.nodes()) byId.put(node.nodeId(), node);
        List<JobGraph.Node> ordered = new ArrayList<>();
        for (long nodeId : graph.order()) {
            JobGraph.Node node = byId.get(nodeId);
            if (node != null) ordered.add(node);
        }
        if (ordered.isEmpty()) ordered.addAll(graph.nodes());
        if (context != null && context.exhausted()) {
            throw new RejectedExecutionException("share budget exhausted");
        }
        for (JobGraph.Node node : ordered) {
            Runnable body = bodies.get(node.key());
            if (body == null) {
                throw new RejectedExecutionException("no body for job " + node.key());
            }
            if (context != null) {
                validate(node);
            }
            WorkerPool.GenericHandle handle = pool.submitGeneric(token -> {
                long started = System.nanoTime();
                try {
                    body.run();
                } finally {
                    double millis = (System.nanoTime() - started) / 1_000_000.0;
                    if (context != null && !context.charge(millis)) {
                        throw new RejectedExecutionException("share budget exhausted");
                    }
                    if (metering != null) {
                        metering.charge(node.worldId(), node.domainId(), millis);
                    }
                }
            }, new NodeCancel(node.nodeId()));
            if (handle == null) {
                throw new RejectedExecutionException("pool refused job " + node.key());
            }
            handles.add(handle);
        }
        return handles;
    }

    private static final class NodeCancel implements GenericCancel {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean();

        private NodeCancel(long nodeId) {
        }

        public boolean cancelled() { return cancelled.get(); }

        public boolean cancel() { return cancelled.compareAndSet(false, true); }
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
