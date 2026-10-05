/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One tick's jobs, frozen. The order is part of the graph and not something a reader recomputes:
 * {@link #order} is the topological order the freeze produced, {@link #ready} is the set without a
 * predecessor, and every node carries its successors and the position it holds in the order. */
public record JobGraph(long tickIndex, long planSequence, long worldSetGeneration, List<Node> nodes,
                       List<Long> order, List<Long> ready, List<AffinityGroup> affinity,
                       int queueCap, int splitIntents) {

    public JobGraph {
        nodes = List.copyOf(nodes);
        order = List.copyOf(order);
        ready = List.copyOf(ready);
        affinity = List.copyOf(affinity);
    }

    /** One job of the graph. The node is the frozen form of a declaration: the handle is what the
     * declaring domain knows the job by, and the key is what a later tick can match it on. */
    public record Node(long nodeId, String key, long handle, String worldId, String domainId,
                       int level, List<String> predecessors, List<Long> successors, int priority,
                       String affinity, String cancelScope, List<JobDeclaration.DomainRef> readSet,
                       List<JobDeclaration.DomainRef> writeSet, ShareClass shareClass,
                       JobDeclaration.SiteClass siteClass, int batchKind, int batchBound,
                       boolean splitIntent, int position) {

        public Node {
            predecessors = List.copyOf(predecessors);
            successors = List.copyOf(successors);
            readSet = List.copyOf(readSet);
            writeSet = List.copyOf(writeSet);
        }

        public boolean root() {
            return predecessors.isEmpty();
        }
    }

    /** The jobs that share one affinity, in the order the graph holds them. */
    public record AffinityGroup(String affinity, List<Long> nodes) {

        public AffinityGroup {
            nodes = List.copyOf(nodes);
        }
    }

    public Node node(long nodeId) {
        for (Node node : nodes) {
            if (node.nodeId() == nodeId) {
                return node;
            }
        }
        return null;
    }

    public Node nodeByKey(String key) {
        for (Node node : nodes) {
            if (node.key().equals(key)) {
                return node;
            }
        }
        return null;
    }

    /** The edge count of the graph: one edge per true predecessor. */
    public int edgeCount() {
        int edges = 0;
        for (Node node : nodes) {
            edges += node.predecessors().size();
        }
        return edges;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public boolean empty() {
        return nodes.isEmpty();
    }

    /** The successors of a node, resolved to positions in the order. */
    public List<Integer> successorPositions(long nodeId) {
        Node node = node(nodeId);
        if (node == null) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>();
        for (Long successor : node.successors()) {
            Node target = node(successor);
            if (target != null) {
                positions.add(target.position());
            }
        }
        return positions;
    }

    /** The graph as a name to position map, for a reader that walks the order. */
    public Map<String, Integer> positionsByKey() {
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (Node node : nodes) {
            positions.put(node.key(), node.position());
        }
        return positions;
    }
}
