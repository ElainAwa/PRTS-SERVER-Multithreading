/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.ArrayList;
import java.util.Collections;
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
 * that was never submitted is still visible as terminal once the cohort knows it will not run.
 *
 * <p>Every node also carries a phase witness: whether it entered the cohort, became ready, started,
 * returned from its body, failed its charge and reached a terminal point, how many starts it took and
 * the terminal code that explains the end. The witness is written under the same lock as the state,
 * so a reader cannot observe a terminal state whose witness is still behind. */
public final class ExecutionCohort {

    /** The phase witness of one node. The booleans are monotone; {@code attempt} is zero or one. */
    public record Witness(String key, boolean input, boolean ready, boolean start,
                          boolean bodyReturned, boolean chargeFailed, boolean end, int attempt,
                          String terminalCode) {

        public Witness {
            key = key == null ? "" : key;
            terminalCode = terminalCode == null ? "" : terminalCode;
        }

        /** Whether the node reached its terminal point. */
        public boolean terminal() {
            return end;
        }
    }

    public enum State {
        PENDING,
        READY,
        RUNNING,
        DONE,
        FAILED,
        FAILED_AFTER_EFFECT,
        CANCELLED,
        CANCELLED_AFTER_EFFECT,
        REJECTED
    }

    private static final String DONE_CODE = "";
    private static final String CANCELLED_CODE = "CANCELLED";
    private static final String EXPIRED_CODE = RejectCode.TICK_BUDGET_EXHAUSTED.text();
    private static final String FAILED_CODE = RejectCode.PROGRESS_UNOBSERVED.text();
    private static final String CHARGE_FAILED_CODE = RejectCode.QUOTA_EXCEEDED.text();
    private static final String REJECTED_CODE = RejectCode.QUEUE_CAP_EXCEEDED.text();

    private static final class WitnessState {

        private final String key;
        private boolean input;
        private boolean ready;
        private boolean start;
        private boolean bodyReturned;
        private boolean chargeFailed;
        private boolean end;
        private int attempt;
        private String terminalCode = "";

        private WitnessState(String key, boolean input) {
            this.key = key;
            this.input = input;
        }

        private Witness view() {
            return new Witness(key, input, ready, start, bodyReturned, chargeFailed, end, attempt,
                terminalCode);
        }
    }

    private final JobGraph graph;
    private final Map<String, JobGraph.Node> nodes = new LinkedHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
    private final Map<String, AtomicBoolean> terminals = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelRequests = new ConcurrentHashMap<>();
    private final Map<String, String> cancelCodes = new ConcurrentHashMap<>();
    private final Map<String, WitnessState> witnessStates = new LinkedHashMap<>();
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
            cancelRequests.put(key, new AtomicBoolean());
            witnessStates.put(key, new WitnessState(key, true));
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

    /** The graph this cohort was built from. */
    public JobGraph graph() { return graph; }

    /** All node handles in graph order, including nodes not submitted yet. */
    public List<WorkerPool.GenericHandle> handles() { return List.copyOf(handles); }

    /** The handle for one node key, or null when the graph has no such node. */
    public WorkerPool.GenericHandle handle(String key) {
        return handlesByKey.get(key);
    }

    /** The current state of one node key, or null for an unknown key. */
    public State state(String key) { return states.get(key); }

    /** True once every node has reached a terminal state. */
    public boolean done() { return done.getCount() == 0L; }

    /** Waits up to the timeout for every node to reach a terminal state. */
    public boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    /** Number of nodes submitted to the pool. */
    public int accepted() { return accepted.get(); }
    /** Number of nodes rejected before execution. */
    public int rejected() { return rejected.get(); }
    /** Number of nodes that failed, including failed-after-effect. */
    public int failed() { return failed.get(); }
    /** Number of nodes cancelled before or after execution started. */
    public int cancelled() { return cancelled.get(); }

    /** The immutable phase witness of every node, in graph order. */
    public synchronized Map<String, Witness> witness() {
        Map<String, Witness> copy = new LinkedHashMap<>();
        for (Map.Entry<String, WitnessState> entry : witnessStates.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().view());
        }
        return Collections.unmodifiableMap(copy);
    }

    /** The phase witness of one node key, or null for an unknown key. */
    public synchronized Witness witness(String key) {
        WitnessState witness = key == null ? null : witnessStates.get(key);
        return witness == null ? null : witness.view();
    }

    /** The terminal code of every ended node, in graph order. */
    public synchronized Map<String, String> terminals() {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, WitnessState> entry : witnessStates.entrySet()) {
            if (entry.getValue().end) {
                copy.put(entry.getKey(), entry.getValue().terminalCode);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** The terminal code of one node, or an empty string while it is not terminal or unknown. */
    public synchronized String terminalCode(String key) {
        WitnessState witness = key == null ? null : witnessStates.get(key);
        return witness == null || !witness.end ? "" : witness.terminalCode;
    }

    /** Requests cancellation of one node. A node that has not started is terminal immediately; a
     * running node keeps its future until the body exits and then records its real outcome. */
    public synchronized boolean cancel(String key) {
        return cancelInternal(key, CANCELLED_CODE, false);
    }

    /** Requests cancellation of one node whose deadline passed. A node that has not started becomes
     * terminal as CANCELLED with the tick budget code; a running node keeps the same obligation a
     * manual cancellation does and records its real outcome when the body exits. */
    public synchronized boolean expire(String key) {
        return cancelInternal(key, EXPIRED_CODE, true);
    }

    private boolean cancelInternal(String key, String code, boolean expired) {
        if (key == null) return false;
        State state = states.get(key);
        if (state == null || isTerminal(state)) return false;
        AtomicBoolean request = cancelRequests.get(key);
        if (request != null) request.set(true);
        NodeCancel token = cancels.get(key);
        if (token != null) token.cancelled.set(true);
        String reason = code == null ? CANCELLED_CODE : code;
        cancelCodes.putIfAbsent(key, reason);
        if (state == State.RUNNING) return true;
        if (!terminal(key)) return false;
        states.put(key, State.CANCELLED);
        WitnessState witness = witnessStates.get(key);
        if (witness != null) {
            witness.end = true;
            witness.terminalCode = cancelCodes.getOrDefault(key, reason);
        }
        futures.get(key).completeExceptionally(new CancellationException(
            (expired ? "cohort expired " : "cohort cancelled ") + key));
        cancelled.incrementAndGet();
        finish();
        return true;
    }

    GenericCancel token(String key) { return cancels.get(key); }

    synchronized void markReady(String key) {
        State state = states.get(key);
        if (state != State.PENDING) return;
        states.put(key, State.READY);
        WitnessState witness = witnessStates.get(key);
        if (witness != null) witness.ready = true;
    }

    void markAccepted(String key) {
        accepted.incrementAndGet();
    }

    synchronized boolean markStarted(String key) {
        if (states.get(key) != State.READY) return false;
        AtomicBoolean terminal = terminals.get(key);
        if (terminal == null || terminal.get()) return false;
        states.put(key, State.RUNNING);
        WitnessState witness = witnessStates.get(key);
        if (witness != null) {
            witness.start = true;
            witness.attempt++;
        }
        return true;
    }

    synchronized void markRejected(String key) {
        if (!terminal(key)) return;
        states.put(key, State.REJECTED);
        WitnessState witness = witnessStates.get(key);
        if (witness != null) {
            witness.end = true;
            witness.terminalCode = REJECTED_CODE;
        }
        rejected.incrementAndGet();
        futures.get(key).completeExceptionally(new RejectedExecutionException("job rejected " + key));
        finish();
    }

    synchronized void markOutcome(String key, Throwable failure, boolean chargeFailed) {
        if (states.get(key) != State.RUNNING) return;
        if (!terminal(key)) return;
        WitnessState witness = witnessStates.get(key);
        if (witness == null) return;
        witness.bodyReturned = true;
        witness.chargeFailed = chargeFailed;
        State next;
        String code;
        Throwable terminalFailure = null;
        if (failure != null) {
            next = State.FAILED;
            code = FAILED_CODE;
            terminalFailure = failure;
            failed.incrementAndGet();
        } else if (chargeFailed) {
            next = State.FAILED_AFTER_EFFECT;
            code = CHARGE_FAILED_CODE;
            terminalFailure = new RejectedExecutionException("share budget exhausted");
            failed.incrementAndGet();
        } else if (cancelRequests.get(key).get()) {
            next = State.CANCELLED_AFTER_EFFECT;
            code = cancelCodes.getOrDefault(key, CANCELLED_CODE);
            terminalFailure = new CancellationException("cohort cancelled while running " + key);
            cancelled.incrementAndGet();
        } else {
            next = State.DONE;
            code = DONE_CODE;
        }
        states.put(key, next);
        witness.end = true;
        witness.terminalCode = code;
        if (terminalFailure != null) {
            futures.get(key).completeExceptionally(terminalFailure);
        } else {
            futures.get(key).complete(null);
        }
        finish();
    }

    void registerWorkerHandle(String key, WorkerPool.GenericHandle handle) {
        workerHandles.put(key, handle);
    }

    private boolean terminal(String key) {
        AtomicBoolean flag = terminals.get(key);
        return flag != null && flag.compareAndSet(false, true);
    }

    private void finish() {
        if (terminalCount.incrementAndGet() == nodes.size()) done.countDown();
    }

    private static boolean isTerminal(State state) {
        return state == State.DONE || state == State.FAILED || state == State.FAILED_AFTER_EFFECT
            || state == State.CANCELLED || state == State.CANCELLED_AFTER_EFFECT
            || state == State.REJECTED;
    }

    private final class NodeCancel implements GenericCancel {
        private final String key;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private NodeCancel(String key) { this.key = key; }

        @Override
        public boolean cancelled() {
            return cancelled.get() || cancelRequests.get(key).get()
                || isTerminal(states.get(key));
        }

        @Override
        public boolean cancel() {
            return ExecutionCohort.this.cancel(key);
        }
    }
}
