/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.graph;

import io.izzel.arclight.common.prts.kernel.jobs.RecomputeUnbounded;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The incremental recompute engine of the two in-game product graphs: node and edge topology is
 * versioned, a change is confined to the affected component, the business output (component id,
 * reach distance and edge load) is recomputed there under a declared budget, and a full rebuild
 * without such a budget is refused and counted by the existing unbounded-recompute recorder. The
 * engine never counts a kernel job graph as its input or output. */
public final class IncrementalGraphEngine {

    /** The two real in-game graph kinds this engine serves. */
    public enum GraphKind {
        DEVICE_TRANSFER_NETWORK,
        POI_NAVIGATION_GRAPH
    }

    /** One product graph request: its world, graph kind, network, topology revision and ordinal. */
    public record Identity(String worldId, long worldIncarnation, GraphKind kind, String networkId,
                           long topologyRevision, long logicalUpdateOrdinal) {

        public Identity {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(networkId, "networkId");
            if (worldId.isEmpty() || networkId.isEmpty()) {
                throw new IllegalArgumentException("a product graph names its world and network");
            }
            if (worldIncarnation < 0L || topologyRevision < 0L || logicalUpdateOrdinal < 0L) {
                throw new IllegalArgumentException("a product graph cannot name a negative lineage");
            }
        }
    }

    /** The business output of one recompute: component, reach distance and per-edge load. */
    public record Result(boolean computed, String reason, long scannedNodes,
                         Map<Long, Long> componentByNode, Map<Long, Long> distanceByNode,
                         Map<String, Long> edgeLoad) {
    }

    private final Map<Long, Map<Long, Long>> edges = new LinkedHashMap<>();
    private final Set<Long> nodes = new LinkedHashSet<>();
    private final Set<Long> dirty = new LinkedHashSet<>();
    private final Map<Long, Long> component = new LinkedHashMap<>();
    private final Map<Long, Long> distance = new LinkedHashMap<>();
    private final Map<String, Long> load = new LinkedHashMap<>();
    private long topologyRevision;
    private long refusedUnbudgeted;
    private long computedIncremental;
    private long computedFull;

    public synchronized void addNode(long node) {
        if (nodes.add(node)) {
            topologyRevision++;
            dirty.add(node);
        }
    }

    public synchronized void addEdge(long from, long to, long weight) {
        addNode(from);
        addNode(to);
        edges.computeIfAbsent(from, key -> new LinkedHashMap<>()).put(to, weight);
        edges.computeIfAbsent(to, key -> new LinkedHashMap<>()).put(from, weight);
        topologyRevision++;
        dirty.add(from);
    }

    public synchronized void removeEdge(long from, long to) {
        Map<Long, Long> forward = edges.get(from);
        if (forward != null && forward.remove(to) != null) {
            topologyRevision++;
            dirty.add(from);
            load.remove(from + "->" + to);
            load.remove(to + "->" + from);
        }
        Map<Long, Long> backward = edges.get(to);
        if (backward != null && backward.remove(from) != null) {
            topologyRevision++;
            dirty.add(to);
        }
    }

    public synchronized void removeNode(long node) {
        nodes.remove(node);
        edges.remove(node);
        for (Map<Long, Long> neighbours : edges.values()) {
            neighbours.remove(node);
        }
        load.keySet().removeIf(key -> key.startsWith(node + "->") || key.endsWith("->" + node));
        topologyRevision++;
        dirty.add(node);
    }

    public synchronized Identity identity(GraphKind kind, String worldId, long worldIncarnation,
                                          String networkId, long ordinal) {
        return new Identity(worldId, worldIncarnation, kind, networkId, topologyRevision, ordinal);
    }

    /** Recomputes only the affected components under the declared budget; no silent full scan. */
    public synchronized Result incremental(Identity identity, long budget) {
        Objects.requireNonNull(identity, "identity");
        if (identity.topologyRevision() != topologyRevision) {
            return new Result(false, "stale-topology-revision", 0L, Map.of(), Map.of(), Map.of());
        }
        if (budget <= 0L) {
            RecomputeUnbounded.noteUnbudgetedStart();
            refusedUnbudgeted++;
            return new Result(false, "unbudgeted-full-recompute", 0L, Map.of(), Map.of(), Map.of());
        }
        Set<Long> affected = affectedComponent();
        if (affected.size() > budget) {
            RecomputeUnbounded.noteUnbudgetedStart();
            refusedUnbudgeted++;
            return new Result(false, "over-budget-incremental", 0L, Map.of(), Map.of(), Map.of());
        }
        recompute(affected);
        dirty.clear();
        RecomputeUnbounded.noteBudgetedBuild();
        computedIncremental++;
        return snapshot("incremental", affected.size());
    }

    /** A full rebuild is admitted only with a budget that covers every node. */
    public synchronized Result full(Identity identity, long budget) {
        Objects.requireNonNull(identity, "identity");
        if (identity.topologyRevision() != topologyRevision) {
            return new Result(false, "stale-topology-revision", 0L, Map.of(), Map.of(), Map.of());
        }
        if (budget <= 0L || budget < nodes.size()) {
            RecomputeUnbounded.noteUnbudgetedStart();
            refusedUnbudgeted++;
            return new Result(false, "unbudgeted-full-recompute", 0L, Map.of(), Map.of(), Map.of());
        }
        component.clear();
        distance.clear();
        load.clear();
        recompute(nodes);
        dirty.clear();
        RecomputeUnbounded.noteBudgetedBuild();
        computedFull++;
        return snapshot("full", nodes.size());
    }

    public synchronized long topologyRevision() {
        return topologyRevision;
    }

    public synchronized long refusedUnbudgeted() {
        return refusedUnbudgeted;
    }

    public synchronized long computedIncremental() {
        return computedIncremental;
    }

    public synchronized long computedFull() {
        return computedFull;
    }

    private Set<Long> affectedComponent() {
        Set<Long> affected = new LinkedHashSet<>();
        for (long seed : dirty) {
            if (!nodes.contains(seed)) {
                continue;
            }
            collect(seed, affected);
        }
        return affected;
    }

    private void collect(long node, Set<Long> componentNodes) {
        if (!nodes.contains(node) || !componentNodes.add(node)) {
            return;
        }
        for (long neighbour : edges.getOrDefault(node, Map.of()).keySet()) {
            collect(neighbour, componentNodes);
        }
    }

    private void recompute(Set<Long> affected) {
        for (long node : affected) {
            component.remove(node);
            distance.remove(node);
        }
        for (long seed : affected) {
            if (component.containsKey(seed)) {
                continue;
            }
            Set<Long> members = new LinkedHashSet<>();
            collect(seed, members);
            long group = members.stream().mapToLong(Long::longValue).min().orElse(seed);
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            component.put(group, group);
            distance.put(group, 0L);
            queue.add(group);
            while (!queue.isEmpty()) {
                long node = queue.poll();
                for (long next : neighboursOf(node)) {
                    if (distance.containsKey(next)
                        && distance.get(next) <= distance.get(node) + 1L) {
                        continue;
                    }
                    if (!members.contains(next)) {
                        continue;
                    }
                    component.put(next, group);
                    distance.put(next, distance.get(node) + 1L);
                    queue.add(next);
                }
            }
        }
        for (long node : affected) {
            for (Map.Entry<Long, Long> edge : edges.getOrDefault(node, Map.of()).entrySet()) {
                load.put(node + "->" + edge.getKey(), edge.getValue());
            }
        }
    }

    private List<Long> neighboursOf(long node) {
        return new ArrayList<>(edges.getOrDefault(node, Map.of()).keySet());
    }

    private Result snapshot(String reason, long scanned) {
        return new Result(true, reason, scanned, Map.copyOf(component), Map.copyOf(distance),
            Map.copyOf(load));
    }
}
