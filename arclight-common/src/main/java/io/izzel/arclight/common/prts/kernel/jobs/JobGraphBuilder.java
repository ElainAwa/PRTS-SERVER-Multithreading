/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Freezes declarations into a graph. Every decision here is a function of the declarations alone:
 * nodes are numbered in a sorted order, the ready set is drained through a total comparator and the
 * cycle check is the count of nodes the drain could not reach, so two freezes of the same
 * declarations - in any input order - produce the same graph and the same order.
 *
 * <p>The bounded recompute is the planning side entry: it answers whether the declarations of this
 * tick still freeze to the graph the previous tick froze, and it charges every structural step it
 * takes against two explicit work budgets. A scan that proves the declarations unchanged re-stamps
 * the previous graph without rebuilding it; a changed set is rebuilt only inside the wider fallback
 * budget; a request that cannot pay for its work is refused and publishes no graph. */
public final class JobGraphBuilder {

    /** What one freeze produced. A refused freeze carries the code of the refusal and the item it
     * was refused on; no partial graph is published. */
    public record Freeze(JobGraph graph, RejectCode code, String item, int refusedDeclarations) {

        /** Whether the freeze produced a usable graph; a refused freeze answers false and carries no
         * graph. */
        public boolean ok() {
            return graph != null;
        }

        static Freeze refused(RejectCode code, String item, int refused) {
            return new Freeze(null, code, item, refused);
        }
    }

    /** The shape one bounded recompute answered with. {@link #REUSED} rebuilt nothing,
     * {@link #BOOTSTRAP} built the first graph of a planning life and {@link #FULL_FALLBACK} rebuilt
     * a graph whose declarations changed. */
    public enum RecomputeMode {
        REUSED,
        BOOTSTRAP,
        FULL_FALLBACK
    }

    /** One bounded recompute request: the previous graph (null on the first plan of a life), the
     * declarations of this tick, the freeze inputs and the two explicit work budgets. Both budgets
     * are structural work units, not wall clock: a budget of zero is a request without a budget and
     * is refused instead of being spent. */
    public record RecomputeRequest(JobGraph previous, List<JobDeclaration> declarations,
                                   long tickIndex, long planSequence, long worldSetGeneration,
                                   int queueCap, int scanBudget, int fullBudget) {

        public RecomputeRequest {
            declarations = List.copyOf(declarations == null ? List.of() : declarations);
        }
    }

    /** What one bounded recompute produced. A refusal carries the code and the item and no graph.
     * {@code scanWork} is the work the dirty scan spent, {@code fullWork} the work of a rebuild; each
     * is bounded by its own budget and zero when that phase did not run. */
    public record Recompute(JobGraph graph, RecomputeMode mode, RejectCode code, String item,
                            int scanWork, int scanBudget, int fullWork, int fullBudget,
                            int dirtyNodes) {

        /** Whether this answer produced a graph; a refused answer produced none. */
        public boolean ok() {
            return graph != null;
        }

        /** Whether the request that produced this answer carried both explicit work budgets. */
        public boolean budgeted() {
            return scanBudget > 0 && fullBudget > 0;
        }
    }

    /** Work units a dirty scan may spend per queue slot: one per previous node indexed, one per
     * declaration looked up and compared and one per declared edge compared. */
    public static final int SCAN_WORK_PER_SLOT = 8;

    /** Work units a full freeze may spend per queue slot: snapshot, validation, numbering, linking
     * and ordering each touch every declaration, node and edge once. */
    public static final int FULL_WORK_PER_SLOT = 64;

    /** The declared scan budget of one queue bound; both budgets are derived from the bound the
     * intake already refuses declarations above, so no new switch is introduced. */
    public static int scanWorkBudget(int queueCap) {
        return SCAN_WORK_PER_SLOT * Math.max(1, queueCap);
    }

    /** The declared fallback budget of one queue bound. */
    public static int fullWorkBudget(int queueCap) {
        return FULL_WORK_PER_SLOT * Math.max(1, queueCap);
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

    /** Freezes one tick's declarations without a caller budget: the offline entry the fixtures and
     * the self check use. The planning period goes through {@link #recompute} instead, so a
     * production full build always carries its work budget. */
    public static Freeze freeze(List<JobDeclaration> declarations, long tickIndex, long planSequence,
                                long worldSetGeneration, int queueCap) {
        return freeze(declarations, tickIndex, planSequence, worldSetGeneration, queueCap,
            new Work(Integer.MAX_VALUE));
    }

    /** Answers one bounded recompute: reuses the previous graph when the declarations still freeze to
     * it, otherwise rebuilds it inside the explicit fallback budget, and refuses when it cannot pay. */
    public static Recompute recompute(RecomputeRequest request) {
        if (request == null) {
            return new Recompute(null, null, RejectCode.COUNTER_MISSING, "recompute request",
                0, 0, 0, 0, 0);
        }
        List<JobDeclaration> declarations = request.declarations();
        int scanBudget = request.scanBudget();
        int fullBudget = request.fullBudget();
        int cap = Math.max(1, request.queueCap());
        if (declarations.size() > cap) {
            return new Recompute(null, null, RejectCode.QUEUE_CAP_EXCEEDED,
                "declarations=" + declarations.size() + " cap=" + cap, 0, scanBudget, 0, fullBudget,
                0);
        }
        if (request.previous() == null) {
            return full(request, cap, scanBudget, fullBudget, RecomputeMode.BOOTSTRAP, 0,
                declarations.size());
        }
        if (scanBudget <= 0) {
            // A change cannot be told from no change without a scan: only an explicitly budgeted
            // full rebuild may answer, and a request with neither budget is refused.
            return full(request, cap, scanBudget, fullBudget, RecomputeMode.FULL_FALLBACK, 0, -1);
        }
        Work scan = new Work(scanBudget);
        int dirty = dirtyScan(request.previous(), declarations, scan);
        if (dirty < 0) {
            return full(request, cap, scanBudget, fullBudget, RecomputeMode.FULL_FALLBACK,
                scan.used(), -1);
        }
        if (dirty == 0) {
            JobGraph previous = request.previous();
            JobGraph reused = new JobGraph(request.tickIndex(), request.planSequence(),
                request.worldSetGeneration(), previous.nodes(), previous.order(), previous.ready(),
                previous.affinity(), cap, previous.splitIntents());
            return new Recompute(reused, RecomputeMode.REUSED, null, "", scan.used(), scanBudget,
                0, fullBudget, 0);
        }
        return full(request, cap, scanBudget, fullBudget, RecomputeMode.FULL_FALLBACK, scan.used(),
            dirty);
    }

    private static Recompute full(RecomputeRequest request, int cap, int scanBudget, int fullBudget,
                                  RecomputeMode mode, int scanWork, int dirty) {
        if (fullBudget <= 0) {
            return new Recompute(null, null, RejectCode.RECOMPUTE_UNBOUNDED,
                "a full recompute was asked for without a work budget", scanWork, scanBudget, 0,
                fullBudget, dirty);
        }
        Work work = new Work(fullBudget);
        Freeze freeze = freeze(request.declarations(), request.tickIndex(), request.planSequence(),
            request.worldSetGeneration(), cap, work);
        if (!freeze.ok()) {
            return new Recompute(null, mode, freeze.code(), freeze.item(), scanWork, scanBudget,
                work.used(), fullBudget, dirty);
        }
        return new Recompute(freeze.graph(), mode, null, "", scanWork, scanBudget, work.used(),
            fullBudget, dirty);
    }

    /** Counts the structural work of one dirty scan. Answers the number of changed nodes, or -1 when
     * the scan itself cannot finish inside its budget and the answer is unknown. */
    private static int dirtyScan(JobGraph previous, List<JobDeclaration> declarations, Work work) {
        Map<String, JobGraph.Node> byKey = new LinkedHashMap<>();
        for (JobGraph.Node node : previous.nodes()) {
            if (!work.spend(1)) {
                return -1;
            }
            byKey.put(node.key(), node);
        }
        Set<String> seen = new LinkedHashSet<>();
        int changed = 0;
        int matched = 0;
        for (JobDeclaration declaration : declarations) {
            if (!work.spend(1)) {
                return -1;
            }
            JobGraph.Node node = byKey.get(declaration.key());
            if (!work.spend(1)) {
                return -1;
            }
            boolean matches = false;
            if (node != null) {
                if (!work.spend(declaration.predecessors().size())) {
                    return -1;
                }
                matches = matches(node, declaration);
            }
            if (!matches || !seen.add(declaration.key())) {
                changed++;
            } else {
                matched++;
            }
        }
        if (matched != previous.nodeCount()) {
            changed++;
        }
        return changed;
    }

    /** Whether one previous node still carries exactly what one declaration declares; every field
     * the graph exposes is compared, so a reused graph answers the same as a rebuild would. */
    private static boolean matches(JobGraph.Node node, JobDeclaration declaration) {
        return node.handle() == declaration.handle()
            && node.worldId().equals(declaration.worldId())
            && node.domainId().equals(declaration.domainId())
            && node.level() == declaration.level()
            && node.affinity().equals(declaration.affinity())
            && node.priority() == declaration.priority()
            && node.cancelScope().equals(declaration.cancelScope())
            && node.readSet().equals(declaration.readSet())
            && node.writeSet().equals(declaration.writeSet())
            && node.ownerDemands().equals(declaration.ownerDemands())
            && node.shareClass() == declaration.shareClass()
            && node.siteClass() == declaration.siteClass()
            && node.batchKind() == declaration.batchKind()
            && node.batchBound() == declaration.batchBound()
            && node.splitIntent() == declaration.crossesDomains()
            && Objects.equals(node.range(), declaration.range())
            && node.predecessors().equals(declaration.predecessors());
    }

    /** The structural counter of one bounded recompute: one unit is one declaration, node or edge
     * touched. The counter never runs past its budget; the caller answers the refusal. */
    private static final class Work {

        private final int budget;
        private int used;

        private Work(int budget) {
            this.budget = budget;
        }

        private boolean spend(int units) {
            if (units < 0 || (long) used + units > budget) {
                return false;
            }
            used += units;
            return true;
        }

        private int used() {
            return used;
        }
    }

    private static Freeze exhausted(Work work) {
        return Freeze.refused(RejectCode.SCALE_BUDGET_EXCEEDED,
            "work budget exhausted after " + work.used() + " units", 0);
    }

    /** Freezes one tick's declarations against one work budget. */
    private static Freeze freeze(List<JobDeclaration> declarations, long tickIndex,
                                 long planSequence, long worldSetGeneration, int queueCap,
                                 Work work) {
        List<JobDeclaration> input = new ArrayList<>();
        for (JobDeclaration declaration : declarations == null ? List.<JobDeclaration>of()
            : declarations) {
            if (!work.spend(1)) {
                return exhausted(work);
            }
            input.add(declaration);
        }
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
            if (!work.spend(1)) {
                return exhausted(work);
            }
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
                if (!work.spend(1)) {
                    return exhausted(work);
                }
                if (!keys.contains(predecessor)) {
                    return Freeze.refused(RejectCode.WRITE_DENIED_NOT_OWNER,
                        "unknown predecessor " + predecessor + " of " + declaration.key(),
                        input.size());
                }
            }
        }

        List<JobDeclaration> sorted = new ArrayList<>(input);
        if (!work.spend(input.size())) {
            return exhausted(work);
        }
        sorted.sort(DECLARATION_ORDER);
        if (!work.spend(input.size())) {
            return exhausted(work);
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        Map<Long, JobDeclaration> byId = new LinkedHashMap<>();
        long nextId = 1L;
        for (JobDeclaration declaration : sorted) {
            if (!work.spend(1)) {
                return exhausted(work);
            }
            ids.put(declaration.key(), nextId);
            byId.put(nextId, declaration);
            nextId++;
        }

        Map<Long, List<Long>> successors = new LinkedHashMap<>();
        Map<Long, Integer> indegree = new LinkedHashMap<>();
        for (JobDeclaration declaration : sorted) {
            if (!work.spend(1)) {
                return exhausted(work);
            }
            long nodeId = ids.get(declaration.key());
            indegree.put(nodeId, declaration.predecessors().size());
            for (String predecessor : declaration.predecessors()) {
                if (!work.spend(1)) {
                    return exhausted(work);
                }
                successors.computeIfAbsent(ids.get(predecessor), key -> new ArrayList<>())
                    .add(nodeId);
            }
        }
        for (List<Long> list : successors.values()) {
            list.sort(Comparator.naturalOrder());
        }

        PriorityQueue<JobGraph.Node> ready = new PriorityQueue<>(DRAIN_ORDER);
        for (JobDeclaration declaration : sorted) {
            long nodeId = ids.get(declaration.key());
            if (indegree.get(nodeId) == 0) {
                if (!work.spend(1)) {
                    return exhausted(work);
                }
                ready.add(node(nodeId, declaration, successors, List.of(), 0));
            }
        }

        List<JobGraph.Node> ordered = new ArrayList<>(sorted.size());
        Map<Long, Integer> positions = new LinkedHashMap<>();
        List<Long> roots = new ArrayList<>();
        while (!ready.isEmpty()) {
            if (!work.spend(1)) {
                return exhausted(work);
            }
            JobGraph.Node next = ready.poll();
            JobGraph.Node placed = new JobGraph.Node(next.nodeId(), next.key(), next.handle(),
                next.worldId(), next.domainId(), next.level(), next.predecessors(), next.successors(),
                next.priority(), next.affinity(), next.cancelScope(), next.readSet(), next.writeSet(),
                next.ownerDemands(), next.shareClass(), next.siteClass(), next.batchKind(),
                next.batchBound(), next.splitIntent(), ordered.size(), next.range());
            ordered.add(placed);
            positions.put(placed.nodeId(), placed.position());
            if (placed.root()) {
                roots.add(placed.nodeId());
            }
            for (Long successor : placed.successors()) {
                if (!work.spend(1)) {
                    return exhausted(work);
                }
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
            if (!work.spend(1)) {
                return exhausted(work);
            }
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
            declaration.batchBound(), declaration.crossesDomains(), position, declaration.range());
    }
}
