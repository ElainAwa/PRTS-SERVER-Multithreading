/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The accepted prefix of one graph execution: every node has one state and one future, and a node
 * that was never submitted is still visible as terminal once the cohort knows it will not run. */
public final class ExecutionCohort {

    public enum State {
        PENDING,
        READY,
        RUNNING,
        DONE,
        FAILED,
        FAILED_AFTER_EFFECT,
        CANCELLED,
        REJECTED
    }

    private final JobGraph graph;
    private final Map<String, JobGraph.Node> nodes = new LinkedHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> terminals = new ConcurrentHashMap<>();
    private final Map<String, NodeCancel> cancels = new ConcurrentHashMap<>();
    private final Map<String, WorkerPool.GenericHandle> workerHandles = new ConcurrentHashMap<>();
    private final List<WorkerPool.GenericHandle> handles = new ArrayList<>();
    private final Map<String, WorkerPool.GenericHandle> handlesByKey = new ConcurrentHashMap<>();
    private final CountDownLatch done;
    private final AtomicInteger terminalCount = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger cancelled = new AtomicInteger();

    ExecutionCohort(JobGraph graph) {
        this.graph = graph;
        this.done = new CountDownLatch(1);
        for (JobGraph.Node node : graph.nodes()) {
            String key = node.key();
            nodes.put(key, node);
            states.put(key, State.PENDING);
            futures.put(key, new CompletableFuture<>());
            terminals.put(key, new AtomicBoolean());
        }
        for (long id : graph.order()) {
            JobGraph.Node node = graph.node(id);
            if (node == null) continue;
            NodeCancel cancel = new NodeCancel(node.key());
            cancels.put(node.key(), cancel);
            WorkerPool.GenericHandle handle = new WorkerPool.GenericHandle(futures.get(node.key()), cancel);
            handles.add(handle);
            handlesByKey.put(node.key(), handle);
        }
        if (graph.nodes().isEmpty()) done.countDown();
    }

    public JobGraph graph() { return graph; }

    public List<WorkerPool.GenericHandle> handles() { return List.copyOf(handles); }

    public WorkerPool.GenericHandle handle(String key) {
        return handlesByKey.get(key);
    }

    public State state(String key) { return states.get(key); }

    public boolean done() { return done.getCount() == 0L; }

    public boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    public int accepted() { return accepted.get(); }
    public int rejected() { return rejected.get(); }
    public int failed() { return failed.get(); }
    public int cancelled() { return cancelled.get(); }

    /** Requests cancellation of one node; a node already terminal is left alone. */
    public boolean cancel(String key) {
        if (!terminal(key)) return false;
        NodeCancel token = cancels.get(key);
        if (token != null) token.cancelled.set(true);
        states.put(key, State.CANCELLED);
        futures.get(key).completeExceptionally(new CancellationException("cohort cancelled " + key));
        WorkerPool.GenericHandle handle = workerHandles.get(key);
        if (handle != null) handle.cancel();
        cancelled.incrementAndGet();
        finish(key);
        return true;
    }

    GenericCancel token(String key) { return cancels.get(key); }

    void markReady(String key) {
        states.computeIfPresent(key, (ignored, state) ->
            state == State.PENDING ? State.READY : state);
    }

    void markAccepted(String key) {
        accepted.incrementAndGet();
    }

    void markRunning(String key) {
        states.computeIfPresent(key, (ignored, state) ->
            state == State.READY ? State.RUNNING : state);
    }

    void markRejected(String key) {
        if (!terminal(key)) return;
        states.put(key, State.REJECTED);
        rejected.incrementAndGet();
        futures.get(key).completeExceptionally(new RejectedExecutionException("job rejected " + key));
        finish(key);
    }

    void markDone(String key) {
        if (!terminal(key)) return;
        states.put(key, State.DONE);
        futures.get(key).complete(null);
        finish(key);
    }

    void markFailed(String key, Throwable cause) {
        if (!terminal(key)) return;
        states.put(key, State.FAILED);
        failed.incrementAndGet();
        futures.get(key).completeExceptionally(cause == null
            ? new IllegalStateException("job failed " + key) : cause);
        finish(key);
    }

    void markFailedAfterEffect(String key, Throwable cause) {
        if (!terminal(key)) return;
        states.put(key, State.FAILED_AFTER_EFFECT);
        failed.incrementAndGet();
        futures.get(key).completeExceptionally(cause == null
            ? new IllegalStateException("job failed after effect " + key) : cause);
        finish(key);
    }

    void registerWorkerHandle(String key, WorkerPool.GenericHandle handle) {
        workerHandles.put(key, handle);
    }

    private boolean terminal(String key) {
        AtomicBoolean flag = terminals.get(key);
        return flag != null && flag.compareAndSet(false, true);
    }

    private void finish(String key) {
        if (terminalCount.incrementAndGet() == nodes.size()) done.countDown();
    }

    private final class NodeCancel implements GenericCancel {
        private final String key;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private NodeCancel(String key) { this.key = key; }

        @Override
        public boolean cancelled() {
            return cancelled.get() || states.get(key) == State.CANCELLED;
        }

        @Override
        public boolean cancel() {
            return ExecutionCohort.this.cancel(key);
        }
    }
}
