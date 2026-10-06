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

/** Freezes declarations into a graph. Every decision here is a function of the declarations alone:
 * nodes are numbered in a sorted order, the ready set is drained through a total comparator and the
 * cycle check is the count of nodes the drain could not reach, so two freezes of the same
 * declarations - in any input order - produce the same graph and the same order. */
public final class JobGraphBuilder {

    /** What one freeze produced. A refused freeze carries the code of the refusal and the item it
     * was refused on; no partial graph is published. */
    public record Freeze(JobGraph graph, RejectCode code, String item, int refusedDeclarations) {

        public boolean ok() {
            return graph != null;
        }

        static Freeze refused(RejectCode code, String item, int refused) {
            return new Freeze(null, code, item, refused);
        }
    }

    /** Total order of the drain: higher priority first, then world, domain, level, key. The node
     * number is the last term, so no two nodes compare equal. */
    private static final Comparator<JobGraph.Node> DRAIN_ORDER = Comparator
        .comparingInt(JobGraph.Node::priority).reversed()
        .thenComparing(JobGraph.Node::worldId)
        .thenComparing(JobGraph.Node::domainId)
        .thenComparingInt(JobGraph.Node::level)
        .thenComparing(JobGraph.Node::key)
        .thenComparingLong(JobGraph.Node::nodeId);

    private static final Comparator<JobDeclaration> DECLARATION_ORDER = Comparator
        .comparing(JobDeclaration::worldId)
        .thenComparing(JobDeclaration::domainId)
        .thenComparingInt(JobDeclaration::level)
        .thenComparing(JobDeclaration::key);

    private JobGraphBuilder() {
    }

    /** Freezes one tick's declarations. */
    public static Freeze freeze(List<JobDeclaration> declarations, long tickIndex, long planSequence,
                                long worldSetGeneration, int queueCap) {
        List<JobDeclaration> input = new ArrayList<>(declarations == null ? List.of() : declarations);
        int cap = Math.max(1, queueCap);
        if (input.size() > cap) {
            // An unbounded job set is a hidden exhaustion: the intake refuses before the graph is
            // built rather than growing a queue nobody declared a bound for.
            return Freeze.refused(RejectCode.QUEUE_CAP_EXCEEDED,
                "declarations=" + input.size() + " cap=" + cap, input.size());
        }
        int refused = 0;
        Set<String> keys = new LinkedHashSet<>();
        for (JobDeclaration declaration : input) {
            if (!keys.add(declaration.key())) {
                return Freeze.refused(RejectCode.WRITE_DENIED_NOT_OWNER,
                    "duplicate key " + declaration.key(), input.size());
            }
            // A batch bound of zero means "unbounded" in the frozen shape table, which is refused
            // instead of accepted: no queue of this kernel may grow without a declared bound.
            if (declaration.batchBound() <= 0) {
                return Freeze.refused(RejectCode.QUEUE_CAP_EXCEEDED,
                    "unbounded batch " + declaration.key(), input.size());
            }
            if (declaration.crossesWorlds()) {
                return Freeze.refused(RejectCode.CROSS_WORLD_WRITE_DENIED,
                    "cross-world job " + declaration.key(), input.size());
            }
        }
        for (JobDeclaration declaration : input) {
            for (String predecessor : declaration.predecessors()) {
                if (!keys.contains(predecessor)) {
                    return Freeze.refused(RejectCode.WRITE_DENIED_NOT_OWNER,
                        "unknown predecessor " + predecessor + " of " + declaration.key(), input.size());
                }
            }
        }

        List<JobDeclaration> sorted = new ArrayList<>(input);
        sorted.sort(DECLARATION_ORDER);
        Map<String, Long> ids = new LinkedHashMap<>();
        Map<Long, JobDeclaration> byId = new LinkedHashMap<>();
        long nextId = 1L;
        for (JobDeclaration declaration : sorted) {
            ids.put(declaration.key(), nextId);
            byId.put(nextId, declaration);
            nextId++;
        }

        Map<Long, List<Long>> successors = new LinkedHashMap<>();
        Map<Long, Integer> indegree = new LinkedHashMap<>();
        for (JobDeclaration declaration : sorted) {
            long nodeId = ids.get(declaration.key());
            indegree.put(nodeId, declaration.predecessors().size());
            for (String predecessor : declaration.predecessors()) {
                successors.computeIfAbsent(ids.get(predecessor), key -> new ArrayList<>()).add(nodeId);
            }
        }
        for (List<Long> list : successors.values()) {
            list.sort(Comparator.naturalOrder());
        }

        PriorityQueue<JobGraph.Node> ready = new PriorityQueue<>(DRAIN_ORDER);
        for (JobDeclaration declaration : sorted) {
            long nodeId = ids.get(declaration.key());
            if (indegree.get(nodeId) == 0) {
                ready.add(node(nodeId, declaration, successors, List.of(), 0));
            }
        }

        List<JobGraph.Node> ordered = new ArrayList<>(sorted.size());
        Map<Long, Integer> positions = new LinkedHashMap<>();
        List<Long> roots = new ArrayList<>();
        while (!ready.isEmpty()) {
            JobGraph.Node next = ready.poll();
            JobGraph.Node placed = new JobGraph.Node(next.nodeId(), next.key(), next.handle(),
                next.worldId(), next.domainId(), next.level(), next.predecessors(), next.successors(),
                next.priority(), next.affinity(), next.cancelScope(), next.readSet(), next.writeSet(),
                next.ownerDemands(), next.shareClass(), next.siteClass(), next.batchKind(),
                next.batchBound(), next.splitIntent(), ordered.size());
            ordered.add(placed);
            positions.put(placed.nodeId(), placed.position());
            if (placed.root()) {
                roots.add(placed.nodeId());
            }
            for (Long successor : placed.successors()) {
                int left = indegree.get(successor) - 1;
                indegree.put(successor, left);
                if (left == 0) {
                    ready.add(node(successor, byId.get(successor), successors, List.of(), 0));
                }
            }
        }
        if (ordered.size() != sorted.size()) {
            int stuck = sorted.size() - ordered.size();
            return Freeze.refused(RejectCode.DAG_CYCLE, "nodes=" + stuck + " could not be ordered",
                refused);
        }

        List<Long> order = new ArrayList<>(ordered.size());
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        int splitIntents = 0;
        for (JobGraph.Node node : ordered) {
            order.add(node.nodeId());
            groups.computeIfAbsent(node.affinity(), key -> new ArrayList<>()).add(node.nodeId());
            if (node.splitIntent()) {
                splitIntents++;
            }
        }
        List<JobGraph.AffinityGroup> affinity = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<Long>> entry : groups.entrySet()) {
            affinity.add(new JobGraph.AffinityGroup(entry.getKey(), entry.getValue()));
        }
        JobGraph graph = new JobGraph(tickIndex, planSequence, worldSetGeneration, ordered, order,
            roots, affinity, cap, splitIntents);
        return new Freeze(graph, null, "", refused);
    }

    private static JobGraph.Node node(long nodeId, JobDeclaration declaration,
                                      Map<Long, List<Long>> successors, List<Long> ignored,
                                      int position) {
        return new JobGraph.Node(nodeId, declaration.key(), declaration.handle(),
            declaration.worldId(), declaration.domainId(), declaration.level(),
            declaration.predecessors(), successors.getOrDefault(nodeId, List.of()),
            declaration.priority(), declaration.affinity(), declaration.cancelScope(),
            declaration.readSet(), declaration.writeSet(), declaration.ownerDemands(),
            declaration.shareClass(), declaration.siteClass(), declaration.batchKind(),
            declaration.batchBound(), declaration.crossesDomains(), position);
    }
}
