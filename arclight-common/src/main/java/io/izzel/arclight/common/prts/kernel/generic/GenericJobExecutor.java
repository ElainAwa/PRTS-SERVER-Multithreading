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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Domain-neutral executor: one body per JobGraph node, supplied by the owner of that domain. A
 * node is submitted only after all of its true predecessors reached their terminal point.
 *
 * <p>The executor also owns the execution end of a {@link WorkAccount}: it seats every node, moves
 * its pending sub-bucket while it is submitted and running, meters its wall and cpu time and closes
 * it when the node ends. A deadline overload checks the monotonic deadline before every submission
 * and expires what can no longer be submitted; the serial entry keeps one body in flight, walks the
 * frozen graph order and stops waiting at the deadline instead of blocking on the running body. The
 * update entry adds a per-key flight gate: one graph per update key is in flight and a second call
 * with the same key is refused before any body runs. */
public final class GenericJobExecutor {

    private final WorkerPool pool;
    private final JobExecutionContext context;
    private final JobMetering metering;
    private final WorkAccount account;
    private final Map<String, Boolean> updateFlights = new ConcurrentHashMap<>();

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
        return executeCohort(graph, bodies, 0L, null);
    }

    /**
     * Starts one graph execution with an absolute monotonic deadline. The deadline is checked before
     * every submission of a root or successor; a node that cannot be submitted expires with the tick
     * budget code and its successors are cancelled without ever running. Zero means no deadline.
     */
    public Execution executeCohort(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos) {
        return executeCohort(graph, bodies, deadlineNanos, null);
    }

    /**
     * Starts one graph execution and installs the caller's observers on the cohort before any node
     * can be submitted, so a settlement they register cannot miss a node. Null is the plain entry.
     */
    public Execution executeCohort(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos,
                                   Consumer<ExecutionCohort> installer) {
        validate(graph, bodies);
        return start(graph, bodies, deadlineNanos, null, installer);
    }

    /**
     * Starts one graph execution under a per-key flight gate. If the same key is already in flight,
     * the call is refused before any body runs: every node is REJECTED and the attached account
     * closes it as REJECTED_BEFORE_EFFECT. The gate is released when the cohort is terminal, for an
     * empty graph immediately, and when the call fails to start at all.
     */
    public Execution executeUpdate(String updateKey, JobGraph graph, Map<String, Runnable> bodies) {
        return executeUpdate(updateKey, graph, bodies, 0L);
    }

    /** The deadline form of {@link #executeUpdate(String, JobGraph, Map)}. */
    public Execution executeUpdate(String updateKey, JobGraph graph, Map<String, Runnable> bodies,
                                   long deadlineNanos) {
        if (updateKey == null || updateKey.isEmpty()) {
            throw new IllegalArgumentException("update key");
        }
        if (graph == null) {
            throw new IllegalArgumentException("graph");
        }
        if (bodies == null) {
            throw new IllegalArgumentException("bodies");
        }
        if (updateFlights.putIfAbsent(updateKey, Boolean.TRUE) != null) {
            return refuseUpdate(graph);
        }
        try {
            validate(graph, bodies);
            return start(graph, bodies, deadlineNanos, updateKey, null);
        } catch (RuntimeException | Error failure) {
            updateFlights.remove(updateKey);
            throw failure;
        }
    }

    /** Compatibility entry point: walks the frozen graph order with one body in flight. */
    public Execution executeSerial(JobGraph graph, Map<String, Runnable> bodies) {
        return executeSerial(graph, bodies, 0L);
    }

    /**
     * Walks the frozen graph order with one body in flight and the same predecessor gate as the
     * parallel path. A node whose predecessor did not finish done is cancelled, and a deadline that
     * passed before its turn expires it with the tick budget code. With a deadline the caller stops
     * waiting when it passes: the running node stays RUNNING with its future pending, every node
     * that did not start expires, and the call returns without waiting for the body. The caller
     * waits for each node, so this entry is not meant to run on a worker thread.
     */
    public Execution executeSerial(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos) {
        validate(graph, bodies);
        Map<String, Runnable> bodyView = Map.copyOf(bodies);
        ExecutionCohort cohort = new ExecutionCohort(graph);
        installSettlement(cohort);
        seedAccount(cohort);
        for (long nodeId : graph.order()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node == null) continue;
            String key = node.key();
            if (expired(deadlineNanos)) {
                cohort.expire(key);
                continue;
            }
            if (!predecessorsDone(cohort, node)) {
                if (predecessorExpired(cohort, node)) {
                    cohort.expire(key);
                } else {
                    cohort.cancel(key);
                }
                continue;
            }
            ExecutionCohort.State state = cohort.state(key);
            if (state != ExecutionCohort.State.PENDING && state != ExecutionCohort.State.READY) {
                continue;
            }
            cohort.markReady(key);
            if (account != null) account.markReady(key);
            Runnable body = bodyView.get(key);
            if (body == null) {
                cohort.markRejected(key);
                continue;
            }
            WorkerPool.GenericHandle handle = pool.submitGeneric(
                token -> run(cohort, node, body), cohort.token(key));
            if (handle == null) {
                cohort.markRejected(key);
                continue;
            }
            cohort.markAccepted(key);
            cohort.registerWorkerHandle(key, handle);
            if (awaitNode(handle, deadlineNanos)) {
                expireUnfinished(cohort, graph);
                return new Execution(cohort, cohort.handles());
            }
        }
        for (JobGraph.Node node : graph.nodes()) {
            if (!isTerminal(cohort.state(node.key()))) {
                cohort.cancel(node.key());
            }
        }
        if (account != null) {
            account.settle();
        }
        return new Execution(cohort, cohort.handles());
    }

    private Execution start(JobGraph graph, Map<String, Runnable> bodies, long deadlineNanos,
                            String updateKey, Consumer<ExecutionCohort> installer) {
        Map<String, Runnable> bodyView = Map.copyOf(bodies);
        ExecutionCohort cohort = new ExecutionCohort(graph);
        if (updateKey != null) {
            cohort.onComplete(() -> updateFlights.remove(updateKey));
        }
        seedAccount(cohort);
        if (installer != null) {
            installer.accept(cohort);
        }
        Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        for (JobGraph.Node node : graph.nodes()) {
            remaining.put(node.key(), new AtomicInteger(node.predecessors().size()));
        }
        java.util.Set<String> released = ConcurrentHashMap.newKeySet();
        java.util.Set<String> waitingWorkers = ConcurrentHashMap.newKeySet();
        installSettlement(cohort, remaining, bodyView, deadlineNanos, released, waitingWorkers);
        for (JobGraph.Node node : graph.nodes()) {
            String key = node.key();
            cohort.handle(key).future().whenComplete((ignored, error) -> {
                if (error != null) {
                    completed(cohort, remaining, key, bodyView, deadlineNanos, released,
                        waitingWorkers);
                }
            });
        }
        for (long nodeId : graph.ready()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node != null) {
                submit(cohort, remaining, node.key(), bodyView, deadlineNanos, released,
                    waitingWorkers);
            }
        }
        if (graph.nodes().isEmpty() && account != null) {
            account.settle();
        }
        return new Execution(cohort, cohort.handles());
    }

    private Execution refuseUpdate(JobGraph graph) {
        ExecutionCohort cohort = new ExecutionCohort(graph);
        installSettlement(cohort);
        seedAccount(cohort);
        cohort.rejectAllBeforeEffect();
        if (account != null) {
            account.settle();
        }
        return new Execution(cohort, cohort.handles());
    }

    private void installSettlement(ExecutionCohort cohort) {
        if (account == null) return;
        cohort.onSettled(settlement -> {
            WorkAccount.Outcome outcome = outcomeOf(settlement.state());
            if (outcome == null) return;
            if (account.close(accountUnitId(cohort, settlement.key()), outcome,
                settlement.terminalCode())) {
                account.settle();
            }
        });
    }

    /** Registers the settlement observer of one execution: the business closure and, for a node that
     * reaches DONE through a resume, the one successor release. A node completed by its worker also
     * passes through {@link #completed}; the released set keeps the successors released exactly once. */
    private void installSettlement(ExecutionCohort cohort, Map<String, AtomicInteger> remaining,
                                   Map<String, Runnable> bodies, long deadlineNanos,
                                   java.util.Set<String> released,
                                   java.util.Set<String> waitingWorkers) {
        cohort.onSettled(settlement -> {
            if (account != null) {
                WorkAccount.Outcome outcome = outcomeOf(settlement.state());
                if (outcome != null && account.close(accountUnitId(cohort, settlement.key()),
                    outcome, settlement.terminalCode())) {
                    account.settle();
                }
            }
            if (settlement.state() == ExecutionCohort.State.DONE && remaining != null
                && waitingWorkers.contains(settlement.key())) {
                // Only a resumed node releases here: a body that finished normally releases its
                // successors from the pool completion callback, after the worker slot is free.
                releaseSuccessors(cohort, remaining, bodies, deadlineNanos, released,
                    waitingWorkers, cohort.graph().nodeByKey(settlement.key()));
            }
        });
    }

    private void completed(ExecutionCohort cohort, Map<String, AtomicInteger> remaining, String key,
                           Map<String, Runnable> bodies, long deadlineNanos,
                           java.util.Set<String> released,
                           java.util.Set<String> waitingWorkers) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        ExecutionCohort.State state = cohort.state(key);
        if (state == ExecutionCohort.State.WAITING) {
            // The capability provider owns the terminal state; the resume settlement releases the
            // successors and this completion is not a business completion of the node.
            waitingWorkers.add(key);
            return;
        }
        if (state != ExecutionCohort.State.DONE) {
            cascade(cohort, node, key);
            return;
        }
        releaseSuccessors(cohort, remaining, bodies, deadlineNanos, released, waitingWorkers,
            node);
    }

    private void releaseSuccessors(ExecutionCohort cohort, Map<String, AtomicInteger> remaining,
                                   Map<String, Runnable> bodies, long deadlineNanos,
                                   java.util.Set<String> released,
                                   java.util.Set<String> waitingWorkers, JobGraph.Node node) {
        if (node == null || released == null || !released.add(node.key())) {
            return;
        }
        for (Long successor : node.successors()) {
            JobGraph.Node next = cohort.graph().node(successor);
            if (next == null) continue;
            AtomicInteger count = remaining.get(next.key());
            if (count != null && count.decrementAndGet() == 0) {
                submit(cohort, remaining, next.key(), bodies, deadlineNanos, released,
                    waitingWorkers);
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
                        Map<String, Runnable> bodies, long deadlineNanos,
                        java.util.Set<String> released,
                        java.util.Set<String> waitingWorkers) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        if (node == null) return;
        ExecutionCohort.State state = cohort.state(key);
        if (state != ExecutionCohort.State.PENDING && state != ExecutionCohort.State.READY) return;
        if (expired(deadlineNanos)) {
            cohort.expire(key);
            return;
        }
        cohort.markReady(key);
        if (account != null) account.markReady(accountUnitId(cohort, key));
        Runnable body = bodies.get(key);
        if (body == null) {
            cohort.markRejected(key);
            return;
        }
        WorkerPool.GenericHandle handle = pool.submitGeneric(token -> run(cohort, node, body),
            cohort.token(key),
            () -> completed(cohort, remaining, key, bodies, deadlineNanos, released,
                waitingWorkers));
        if (handle == null) {
            cohort.markRejected(key);
            return;
        }
        cohort.markAccepted(key);
        cohort.registerWorkerHandle(key, handle);
    }

    private void run(ExecutionCohort cohort, JobGraph.Node node, Runnable body) {
        if (!cohort.markStarted(node.key())) return;
        if (account != null) account.markRunning(accountUnitId(cohort, node.key()));
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
        if (account != null) account.meter(accountUnitId(cohort, node.key()), wallMs, cpuMs);
        boolean chargeFailed = false;
        try {
            if (context != null) chargeFailed = !context.charge(wallMs);
            if (metering != null) metering.charge(node.worldId(), node.domainId(), wallMs, cpuMs);
        } catch (Throwable t) {
            chargeFailed = true;
            if (failure == null) failure = t;
        }
        if (failure == null && account != null
            && account.bucket(accountUnitId(cohort, node.key()))
                == WorkAccount.Bucket.WAIT_RESULT) {
            // The body handed the unit to a capability provider: the asynchronous completion owns
            // the terminal state, so returning from body.run is not DONE.
            cohort.markWaiting(node.key());
            return;
        }
        cohort.markOutcome(node.key(), failure, chargeFailed);
    }

    private static boolean awaitNode(WorkerPool.GenericHandle handle, long deadlineNanos) {
        try {
            if (deadlineNanos == 0L) {
                handle.future().join();
                return false;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return true;
            }
            handle.future().get(remaining, TimeUnit.NANOSECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (CancellationException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (CompletionException e) {
            return false;
        }
    }

    private static void expireUnfinished(ExecutionCohort cohort, JobGraph graph) {
        for (long nodeId : graph.order()) {
            JobGraph.Node node = graph.node(nodeId);
            if (node != null) {
                cohort.expire(node.key());
            }
        }
    }

    private void seedAccount(ExecutionCohort cohort) {
        if (account == null) return;
        JobGraph graph = cohort.graph();
        for (JobGraph.Node node : graph.nodes()) {
            String unitId = accountUnitId(cohort, node.key());
            account.offer(unitId, node.key(), node.worldId(), node.domainId());
            account.seat(unitId, graph.planSequence(), graph.tickIndex(), node.position());
        }
    }

    /** The one unit identity of a node: its key joined with the declared logical update ordinal. */
    private static String accountUnitId(ExecutionCohort cohort, String key) {
        JobGraph.Node node = cohort.graph().nodeByKey(key);
        return WorkAccount.unitId(key, node == null ? 0L : node.logicalUpdateOrdinal());
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
            case PENDING, READY, RUNNING, WAITING -> null;
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
