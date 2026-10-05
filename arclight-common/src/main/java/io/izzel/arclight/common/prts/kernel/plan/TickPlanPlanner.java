/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds one plan. Every input is a frozen value - the tick, the plan sequence, the world set and
 * its generation, the control frame of the previous tick, the declarations and the share table - and
 * no input carries a timestamp, so no clock can reach the plan. The content hash of the plan is a
 * fold over the plan itself, which makes two plans of the same input comparable without keeping
 * either of them. */
public final class TickPlanPlanner {

    /** What one build was given. */
    public record Input(long tickIndex, long planSequence, long worldSetGeneration,
                        List<String> worlds, List<String> declaredDomains, TickPlanStore.Control control,
                        List<JobDeclaration> declarations, ShareTable shareTable, int queueCap) {

        public Input {
            worlds = List.copyOf(worlds);
            declaredDomains = List.copyOf(declaredDomains);
            declarations = List.copyOf(declarations);
        }
    }

    /** What one build produced. A refused build carries the code and names the item; it publishes no
     * partial plan. */
    public record Result(TickPlan plan, RejectCode code, String item) {

        public boolean ok() {
            return plan != null;
        }

        static Result refused(RejectCode code, String item) {
            return new Result(null, code, item);
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private TickPlanPlanner() {
    }

    /** Builds the plan of one tick. */
    public static Result plan(Input input) {
        if (input == null) {
            return Result.refused(RejectCode.COUNTER_MISSING, "input");
        }
        TickPlanStore.Control control = input.control();
        if (control == null || !control.observed()) {
            // The five values of the previous tick are an input, not a suggestion: a planning period
            // that has none refuses to build instead of planning from zeroes nobody measured.
            return Result.refused(RejectCode.COUNTER_MISSING, "control frame");
        }
        if (input.shareTable() == null) {
            return Result.refused(RejectCode.COUNTER_MISSING, "share table");
        }
        if (input.worldSetGeneration() < control.worldSetGeneration()) {
            return Result.refused(RejectCode.WORLD_LIFECYCLE_DENIED,
                "world set generation " + input.worldSetGeneration() + " < "
                    + control.worldSetGeneration());
        }
        if (input.planSequence() <= control.planSequence()) {
            return Result.refused(RejectCode.COMMIT_ORDER_VIOLATION,
                "plan sequence " + input.planSequence() + " <= " + control.planSequence());
        }
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(input.declarations(),
            input.tickIndex(), input.planSequence(), input.worldSetGeneration(), input.queueCap());
        if (!freeze.ok()) {
            return Result.refused(freeze.code(), freeze.item());
        }
        JobGraph graph = freeze.graph();
        int unknownSites = 0;
        for (JobGraph.Node node : graph.nodes()) {
            if (node.siteClass() == JobDeclaration.SiteClass.UNKNOWN) {
                unknownSites++;
            }
        }

        List<Long> topologicalOrder = new ArrayList<>(graph.order());
        List<TickPlan.CommitStep> commitOrder = new ArrayList<>();
        int position = 0;
        for (String world : worldOrder(input.worlds(), graph)) {
            List<JobGraph.Node> ofWorld = new ArrayList<>();
            for (JobGraph.Node node : graph.nodes()) {
                if (node.worldId().equals(world)) {
                    ofWorld.add(node);
                }
            }
            ofWorld.sort((left, right) -> Integer.compare(left.position(), right.position()));
            for (JobGraph.Node node : ofWorld) {
                commitOrder.add(new TickPlan.CommitStep(position++, node.worldId(), node.domainId(),
                    node.key(), node.nodeId(), node.batchKind(), node.batchBound(), false));
                if (node.splitIntent()) {
                    // A job whose write set spans domains is split into the in-domain job and an
                    // intent: the intent gets its own frozen position, so the channel that executes
                    // it later has an order to follow.
                    commitOrder.add(new TickPlan.CommitStep(position++, node.worldId(), "intent",
                        node.key(), node.nodeId(), node.batchKind(), node.batchBound(), true));
                }
            }
        }

        List<TickPlan.DomainMode> modes = modes(input, graph, unknownSites);
        long hash = contentHash(input, graph, commitOrder, modes, unknownSites);
        TickPlan plan = new TickPlan(input.tickIndex(), input.planSequence(),
            input.worldSetGeneration(), input.worlds(), graph, topologicalOrder, commitOrder,
            input.shareTable(), modes, unknownSites, graph.splitIntents(), hash);
        return new Result(plan, null, "");
    }

    /** Worlds in the order the world set gave them, then any world only a declaration named. */
    private static List<String> worldOrder(List<String> worlds, JobGraph graph) {
        Set<String> ordered = new LinkedHashSet<>(worlds);
        List<String> extra = new ArrayList<>();
        for (JobGraph.Node node : graph.nodes()) {
            if (!ordered.contains(node.worldId())) {
                extra.add(node.worldId());
            }
        }
        extra.sort(String::compareTo);
        ordered.addAll(extra);
        return new ArrayList<>(ordered);
    }

    private static List<TickPlan.DomainMode> modes(Input input, JobGraph graph, int unknownSites) {
        Map<String, List<JobGraph.Node>> byPair = new LinkedHashMap<>();
        for (JobGraph.Node node : graph.nodes()) {
            byPair.computeIfAbsent(node.worldId() + "/" + node.domainId(),
                key -> new ArrayList<>()).add(node);
        }
        Set<String> domains = new LinkedHashSet<>(input.declaredDomains());
        for (JobGraph.Node node : graph.nodes()) {
            domains.add(node.domainId());
        }
        List<String> sortedDomains = new ArrayList<>(domains);
        sortedDomains.sort(String::compareTo);
        boolean degraded = input.control() != null
            && input.control().degradeState() != null
            && input.control().degradeState().startsWith("degraded");
        List<TickPlan.DomainMode> modes = new ArrayList<>();
        for (String world : worldOrder(input.worlds(), graph)) {
            for (String domain : sortedDomains) {
                List<JobGraph.Node> nodes = byPair.get(world + "/" + domain);
                if (nodes == null || nodes.isEmpty()) {
                    modes.add(new TickPlan.DomainMode(world, domain, TickPlan.Mode.DEFERRED,
                        TickPlan.Reason.NO_DECLARATION));
                    continue;
                }
                boolean unknown = false;
                boolean serial = false;
                ShareClass shareClass = null;
                for (JobGraph.Node node : nodes) {
                    unknown |= node.siteClass() == JobDeclaration.SiteClass.UNKNOWN;
                    serial |= node.siteClass() == JobDeclaration.SiteClass.SERIAL;
                    shareClass = node.shareClass();
                }
                if (unknown) {
                    modes.add(new TickPlan.DomainMode(world, domain, TickPlan.Mode.CONSERVATIVE,
                        TickPlan.Reason.UNKNOWN_SITE));
                    continue;
                }
                if (serial) {
                    modes.add(new TickPlan.DomainMode(world, domain, TickPlan.Mode.CONSERVATIVE,
                        TickPlan.Reason.DECLARED_SERIAL));
                    continue;
                }
                if (overrun(input.shareTable(), world, shareClass)) {
                    modes.add(new TickPlan.DomainMode(world, domain, TickPlan.Mode.CONSERVATIVE,
                        TickPlan.Reason.SHARE_EXHAUSTED));
                    continue;
                }
                modes.add(new TickPlan.DomainMode(world, domain,
                    degraded ? TickPlan.Mode.CONSERVATIVE : TickPlan.Mode.ACTIVE,
                    degraded ? TickPlan.Reason.DEGRADED : TickPlan.Reason.NONE));
            }
        }
        return modes;
    }

    private static boolean overrun(ShareTable table, String world, ShareClass shareClass) {
        if (table == null || shareClass == null) {
            return false;
        }
        ShareTable.ShareRow row = table.row(world, shareClass);
        return row != null && row.overrun();
    }

    /** The content hash of one plan: a fold over the identity of the plan and of every step it
     * orders. Two plans of the same input fold to the same value without either being kept. */
    private static long contentHash(Input input, JobGraph graph, List<TickPlan.CommitStep> steps,
                                    List<TickPlan.DomainMode> modes, int unknownSites) {
        long hash = OFFSET_BASIS;
        hash = fold(hash, Long.toString(input.tickIndex()));
        hash = fold(hash, Long.toString(input.planSequence()));
        hash = fold(hash, Long.toString(input.worldSetGeneration()));
        for (String world : input.worlds()) {
            hash = fold(hash, "w:" + world);
        }
        for (JobGraph.Node node : graph.nodes()) {
            hash = fold(hash, "n:" + node.key() + "#" + node.position());
        }
        Map<Integer, TickPlan.CommitStep> sorted = new TreeMap<>();
        for (TickPlan.CommitStep step : steps) {
            sorted.put(step.position(), step);
        }
        for (TickPlan.CommitStep step : sorted.values()) {
            hash = fold(hash, "c:" + step.position() + ":" + step.worldId() + ":" + step.domainId()
                + ":" + step.nodeKey() + ":" + step.intent());
        }
        for (TickPlan.DomainMode mode : modes) {
            hash = fold(hash, "m:" + mode.worldId() + ":" + mode.domainId() + ":" + mode.mode()
                + ":" + mode.reason());
        }
        hash = fold(hash, "u:" + unknownSites);
        return hash;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }
}
