/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Drives a frozen graph. The scheduler hands out one job at a time in the order the graph froze,
 * keeps the jobs of one affinity in that relative order, refuses to hand out more jobs than the
 * declared number in flight, propagates a cancellation along the successors of the cancelled job
 * within its scope, and answers a deadline the executor armed. It reads no wall clock of its own: a
 * deadline and a reading of the clock are both handed to it, so the same graph and the same numbers
 * always produce the same answers. */
public final class JobScheduler {

    /** One job handed out: its node, the lane its affinity puts it on and the job's position in the
     * frozen order. */
    public record Step(long nodeId, String key, String affinity, int lane, int position) {
    }

    /** What cancelling one job reached. A cancellation never leaves the scope of the job it starts
     * from, so a world that fails does not stop the work of another. */
    public record CancelReport(long origin, Set<String> scopes, List<Long> cancelled,
                               List<Long> kept, RejectCode code) {

        public CancelReport {
            scopes = Set.copyOf(scopes);
            cancelled = List.copyOf(cancelled);
            kept = List.copyOf(kept);
        }
    }

    /** The hard timeout gate: armed with a deadline in nanoseconds and asked with a reading of the
     * clock the executor took. The gate never reads the clock itself. */
    public record Gate(boolean armed, long deadlineNanos, long nowNanos) {

        public boolean expired() {
            return armed && nowNanos >= deadlineNanos;
        }
    }

    private static final Comparator<JobGraph.Node> READY_ORDER = Comparator
        .comparingInt(JobGraph.Node::position);

    private final Map<Long, Integer> indegree = new LinkedHashMap<>();
    private final Map<Long, Integer> remaining = new LinkedHashMap<>();
    private final PriorityQueue<JobGraph.Node> readyQueue = new PriorityQueue<>(READY_ORDER);
    private final Set<Long> settled = new LinkedHashSet<>();
    private final Set<Long> cancelledSet = new LinkedHashSet<>();
    private final Map<String, Integer> lanes = new LinkedHashMap<>();
    private JobGraph graph;
    private int inFlightCap = 1;
    private int inFlight;
    private int queuedPeak;
    private long dispatched;
    private long settledCount;
    private long backpressure;
    private long cancelledCount;
    private long timedOut;
    private long drained;

    /** Starts a tick on a frozen graph, bounded by the bound the graph itself declares. */
    public void begin(JobGraph graph) {
        begin(graph, graph == null ? 1 : graph.queueCap());
    }

    /** Starts a tick on a frozen graph with the bound of jobs in flight the executor resolved. The
     * bound is a policy and not a property of the graph: a scheduler may run a graph of many jobs
     * with fewer in flight than the graph declared. */
    public void begin(JobGraph graph, int inFlightCap) {
        this.inFlightCap = Math.max(1, inFlightCap);
        this.graph = graph;
        this.inFlight = 0;
        this.queuedPeak = 0;
        this.drained = 0L;
        this.readyQueue.clear();
        this.indegree.clear();
        this.remaining.clear();
        this.settled.clear();
        this.cancelledSet.clear();
        this.lanes.clear();
        if (graph == null) {
            return;
        }
        int lane = 0;
        for (JobGraph.AffinityGroup group : graph.affinity()) {
            lanes.put(group.affinity(), lane++);
        }
        for (JobGraph.Node node : graph.nodes()) {
            indegree.put(node.nodeId(), node.predecessors().size());
            remaining.put(node.nodeId(), node.predecessors().size());
            if (node.root()) {
                readyQueue.add(node);
            }
        }
        queuedPeak = readyQueue.size();
    }

    public JobGraph graph() {
        return graph;
    }

    /** Takes the next job, or null when nothing is ready. A job is only handed out while fewer than
     * the declared bound are in flight; the refusal is counted and the job stays ready. */
    public Step next() {
        if (graph == null || readyQueue.isEmpty()) {
            return null;
        }
        if (inFlight >= inFlightCap) {
            backpressure++;
            return null;
        }
        JobGraph.Node node = readyQueue.poll();
        if (cancelledSet.contains(node.nodeId())) {
            return next();
        }
        inFlight++;
        dispatched++;
        drained += node.successors().size() == 0 ? 1 : 0;
        return new Step(node.nodeId(), node.key(), node.affinity(),
            lanes.getOrDefault(node.affinity(), 0), node.position());
    }

    /** Settles one handed-out job and releases its successors. */
    public void settle(long nodeId) {
        if (graph == null || settled.contains(nodeId)) {
            return;
        }
        JobGraph.Node node = graph.node(nodeId);
        if (node == null) {
            return;
        }
        settled.add(nodeId);
        settledCount++;
        if (inFlight > 0) {
            inFlight--;
        }
        if (cancelledSet.contains(nodeId)) {
            return;
        }
        for (Long successor : node.successors()) {
            int left = remaining.getOrDefault(successor, 0) - 1;
            remaining.put(successor, left);
            if (left == 0 && !settled.contains(successor) && !cancelledSet.contains(successor)) {
                JobGraph.Node target = graph.node(successor);
                if (target != null) {
                    readyQueue.add(target);
                    queuedPeak = Math.max(queuedPeak, readyQueue.size());
                }
            }
        }
    }

    /** Settles a job that timed out: the gate the executor armed decided it, and the job and the
     * successors inside its scope are cancelled. */
    public CancelReport noteTimedOut(long nodeId) {
        timedOut++;
        return cancel(nodeId);
    }

    /** Cancels one job and everything it can reach inside its own scope. */
    public CancelReport cancel(long nodeId) {
        CancelReport report = propagate(nodeId);
        cancelledCount += report.cancelled().size();
        for (Long node : report.cancelled()) {
            settled.add(node);
            remaining.put(node, 0);
        }
        readyQueue.removeIf(node -> cancelledSet.contains(node.nodeId()));
        return report;
    }

    private CancelReport propagate(long nodeId) {
        JobGraph.Node origin = graph == null ? null : graph.node(nodeId);
        if (origin == null) {
            return new CancelReport(nodeId, Set.of(), List.of(), List.of(),
                RejectCode.WRITE_DENIED_NOT_OWNER);
        }
        Set<String> scopes = new LinkedHashSet<>();
        scopes.add(origin.cancelScope());
        List<Long> cancelled = new ArrayList<>();
        List<Long> kept = new ArrayList<>();
        List<Long> frontier = new ArrayList<>();
        frontier.add(nodeId);
        while (!frontier.isEmpty()) {
            long current = frontier.remove(0);
            if (!cancelledSet.add(current)) {
                continue;
            }
            cancelled.add(current);
            JobGraph.Node node = graph.node(current);
            if (node == null) {
                continue;
            }
            for (Long successor : node.successors()) {
                JobGraph.Node target = graph.node(successor);
                if (target == null) {
                    continue;
                }
                if (!target.cancelScope().equals(origin.cancelScope())) {
                    // The scope is the boundary of a cancellation: a job outside it keeps its work
                    // and is only reported, never silently stopped.
                    kept.add(successor);
                    continue;
                }
                frontier.add(successor);
            }
        }
        return new CancelReport(nodeId, scopes, cancelled, kept, null);
    }

    /** The jobs the graph never handed out because they were cancelled. */
    public Set<Long> cancelledNodes() {
        return Set.copyOf(cancelledSet);
    }

    /** Arms the gate with a deadline the executor took from its own clock. */
    public Gate arm(long deadlineNanos) {
        return new Gate(true, deadlineNanos, 0L);
    }

    /** Asks the gate with a reading the executor took. */
    public static boolean expired(Gate gate, long nowNanos) {
        if (gate == null) {
            return false;
        }
        return new Gate(gate.armed(), gate.deadlineNanos(), nowNanos).expired();
    }

    public int depth() {
        return readyQueue.size();
    }

    public int inFlight() {
        return inFlight;
    }

    public int lanes() {
        return lanes.size();
    }

    public int queuedPeak() {
        return queuedPeak;
    }

    /** The bound of jobs in flight this tick runs with. */
    public int inFlightCap() {
        return inFlightCap;
    }

    public long drainedTerminals() {
        return drained;
    }

    public long dispatched() {
        return dispatched;
    }

    public long settledTotal() {
        return settledCount;
    }

    public long backpressureHits() {
        return backpressure;
    }

    public long cancelledTotal() {
        return cancelledCount;
    }

    public long timedOutTotal() {
        return timedOut;
    }

    public boolean closed() {
        return graph != null && settled.containsAll(nodeIds()) && readyQueue.isEmpty();
    }

    private Set<Long> nodeIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (JobGraph.Node node : graph.nodes()) {
            ids.add(node.nodeId());
        }
        return ids;
    }

    public void reset() {
        begin(null);
        dispatched = 0L;
        settledCount = 0L;
        backpressure = 0L;
        cancelledCount = 0L;
        timedOut = 0L;
    }
}
