/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import java.util.function.Consumer;

/** The accepted prefix of one graph execution: every node has one state and one future, and a node
 * that was never submitted is still visible as terminal once the cohort knows it will not run.
 *
 * <p>Every node also carries a phase witness: whether it entered the cohort, became ready, started,
 * declared its effect possible, was asked to cancel, returned from its body, failed its charge and
 * reached a terminal point, how many starts it took and the terminal code that explains the end. The
 * witness is written under the same lock as the state, so a reader cannot observe a terminal state
 * whose witness is still behind. The effect and cancellation flags are monotone. */
public final class ExecutionCohort {

    /** The phase witness of one node. Every boolean is monotone; {@code attempt} is zero or one. */
    public record Witness(String key, boolean input, boolean ready, boolean start,
                          boolean bodyReturned, boolean chargeFailed, boolean effectPossible,
                          boolean cancelRequested, boolean end, int attempt, String terminalCode) {

        public Witness {
            key = key == null ? "" : key;
            terminalCode = terminalCode == null ? "" : terminalCode;
        }

        /** The witness shape before the effect and cancellation flags existed. */
        public Witness(String key, boolean input, boolean ready, boolean start,
                       boolean bodyReturned, boolean chargeFailed, boolean end, int attempt,
                       String terminalCode) {
            this(key, input, ready, start, bodyReturned, chargeFailed, false, false, end, attempt,
                terminalCode);
        }

        /** Whether the node reached its terminal point. */
        public boolean terminal() {
            return end;
        }
    }

    /** One node's terminal settlement: the state it reached and the code that explains it. */
    public record Settlement(String key, State state, String terminalCode) {

        public Settlement {
            key = key == null ? "" : key;
            terminalCode = terminalCode == null ? "" : terminalCode;
        }
    }

    public enum State {
        PENDING,
        READY,
        RUNNING,
        WAITING,
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
        private boolean effectPossible;
        private boolean cancelRequested;
        private boolean end;
        private int attempt;
        private String terminalCode = "";

        private WitnessState(String key, boolean input) {
            this.key = key;
            this.input = input;
        }

        private Witness view() {
            return new Witness(key, input, ready, start, bodyReturned, chargeFailed,
                effectPossible, cancelRequested, end, attempt, terminalCode);
        }
    }

    private final JobGraph graph;
    // The node indexes are populated in the constructor before workers can see the cohort.
    private final Map<String, JobGraph.Node> nodesByKey = new LinkedHashMap<>();
    private final Map<Long, JobGraph.Node> nodesById;
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
    private final List<Consumer<Settlement>> settlementHooks = new ArrayList<>();
    private final List<Runnable> completionHooks = new ArrayList<>();
    private final CountDownLatch done;
    private final AtomicInteger terminalCount = new AtomicInteger();
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger cancelled = new AtomicInteger();

    ExecutionCohort(JobGraph graph) {
        this.graph = graph;
        this.done = new CountDownLatch(1);
        this.nodesById = HashMap.newHashMap(graph.nodeCount());
        for (JobGraph.Node node : graph.nodes()) {
            String key = node.key();
            nodesByKey.putIfAbsent(key, node);
            nodesById.putIfAbsent(node.nodeId(), node);
            states.put(key, State.PENDING);
            futures.put(key, new CompletableFuture<>());
            terminals.put(key, new AtomicBoolean());
            cancelRequests.put(key, new AtomicBoolean());
            witnessStates.put(key, new WitnessState(key, true));
        }
        for (long id : graph.order()) {
            JobGraph.Node node = node(id);
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

    JobGraph.Node node(long id) {
        return nodesById.get(id);
    }

    JobGraph.Node nodeByKey(String key) {
        return nodesByKey.get(key);
    }

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

    /** Registers an observer that receives every node's terminal settlement exactly once. A hook
     * registered after a node settled does not see that node, so callers register before the nodes
     * can run. Hooks run under the cohort lock and must not wait on the cohort. */
    public synchronized void onSettled(Consumer<Settlement> hook) {
        if (hook != null) {
            settlementHooks.add(hook);
        }
    }

    /** Registers a one-shot action for the moment every node is terminal. An empty or already
     * settled cohort runs the action immediately. */
    synchronized void onComplete(Runnable hook) {
        if (hook == null) {
            return;
        }
        if (done.getCount() == 0L) {
            runSafely(hook);
            return;
        }
        completionHooks.add(hook);
    }

    /** Declares that the node may now produce its effect. A terminal node keeps the witness it
     * settled with, so a late declaration cannot turn a no-effect cancellation into an after-effect
     * one. */
    public synchronized void markEffectPossible(String key) {
        State state = key == null ? null : states.get(key);
        if (state == null || isTerminal(state)) return;
        WitnessState witness = witnessStates.get(key);
        if (witness != null) witness.effectPossible = true;
    }

    /** Whether the node declared that its effect became possible. */
    public synchronized boolean effectPossible(String key) {
        WitnessState witness = key == null ? null : witnessStates.get(key);
        return witness != null && witness.effectPossible;
    }

    /** Requests cancellation of one node. A node that has not started is terminal immediately; a
     * running node keeps its future until the body exits and then records CANCELLED_AFTER_EFFECT
     * when the body declared its effect possible, or CANCELLED when it did not. */
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
        WitnessState witness = witnessStates.get(key);
        if (witness != null) witness.cancelRequested = true;
        NodeCancel token = cancels.get(key);
        if (token != null) token.cancelled.set(true);
        String reason = code == null ? CANCELLED_CODE : code;
        cancelCodes.putIfAbsent(key, reason);
        if (state == State.RUNNING) return true;
        if (!terminal(key)) return false;
        cancelled.incrementAndGet();
        settle(key, State.CANCELLED, cancelCodes.getOrDefault(key, reason),
            new CancellationException((expired ? "cohort expired " : "cohort cancelled ") + key));
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

    /** Leaves an asynchronous node non-terminal while its capability provider answers. */
    synchronized void markWaiting(String key) {
        State state = key == null ? null : states.get(key);
        if (state != State.RUNNING) return;
        states.put(key, State.WAITING);
    }

    synchronized void markRejected(String key) {
        State state = key == null ? null : states.get(key);
        if (state == null || isTerminal(state) || state == State.RUNNING) return;
        if (!terminal(key)) return;
        rejected.incrementAndGet();
        settle(key, State.REJECTED, REJECTED_CODE,
            new RejectedExecutionException("job rejected " + key));
    }

    /** Refuses every node that has not started, before any body of the cohort can run. This is the
     * admission answer of a cohort that loses a flight gate or cannot be started at all; a node
     * already running is left for its own body to settle. */
    public synchronized int rejectAllBeforeEffect() {
        int count = 0;
        for (String key : nodesByKey.keySet()) {
            State state = states.get(key);
            if (state != State.PENDING && state != State.READY) continue;
            if (!terminal(key)) continue;
            rejected.incrementAndGet();
            settle(key, State.REJECTED, REJECTED_CODE,
                new RejectedExecutionException("job rejected " + key));
            count++;
        }
        return count;
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
            next = witness.effectPossible ? State.CANCELLED_AFTER_EFFECT : State.CANCELLED;
            code = cancelCodes.getOrDefault(key, CANCELLED_CODE);
            terminalFailure = new CancellationException("cohort cancelled while running " + key);
            cancelled.incrementAndGet();
        } else {
            next = State.DONE;
            code = DONE_CODE;
        }
        settle(key, next, code, terminalFailure);
    }

    void registerWorkerHandle(String key, WorkerPool.GenericHandle handle) {
        workerHandles.put(key, handle);
    }

    private void settle(String key, State state, String code, Throwable failure) {
        states.put(key, state);
        WitnessState witness = witnessStates.get(key);
        if (witness != null) {
            witness.end = true;
            witness.terminalCode = code == null ? "" : code;
        }
        notifySettled(key, state, code);
        CompletableFuture<Void> future = futures.get(key);
        if (failure != null) {
            future.completeExceptionally(failure);
        } else {
            future.complete(null);
        }
        finish();
    }

    private void notifySettled(String key, State state, String code) {
        if (settlementHooks.isEmpty()) return;
        Settlement settlement = new Settlement(key, state, code);
        for (Consumer<Settlement> hook : List.copyOf(settlementHooks)) {
            try {
                hook.accept(settlement);
            } catch (Throwable ignored) {
                // A settlement observer must not be able to leave the node non-terminal.
            }
        }
    }

    private void finish() {
        if (terminalCount.incrementAndGet() != nodesByKey.size()) return;
        for (Runnable hook : List.copyOf(completionHooks)) {
            runSafely(hook);
        }
        done.countDown();
    }

    private static void runSafely(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable ignored) {
            // Completion observers cannot keep a settled cohort from opening its latch.
        }
    }

    private boolean terminal(String key) {
        AtomicBoolean flag = terminals.get(key);
        return flag != null && flag.compareAndSet(false, true);
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

        @Override
        public void markEffectPossible() {
            ExecutionCohort.this.markEffectPossible(key);
        }

        @Override
        public boolean effectPossible() {
            return ExecutionCohort.this.effectPossible(key);
        }
    }
}
