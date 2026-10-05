/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;

import java.util.List;

/** One tick, frozen. The plan is built once, published once and then only read: the job graph, its
 * topological order, the commit order derived from it and grouped by world, the share table it was
 * planned against, the mode of every (world, domain) pair and the world set generation. */
public record TickPlan(long tickIndex, long planSequence, long worldSetGeneration,
                       List<String> worlds, JobGraph graph, List<Long> topologicalOrder,
                       List<CommitStep> commitOrder, ShareTable shareTable,
                       List<DomainMode> domainModes, int unknownSites, int splitIntents,
                       long contentHash) {

    public TickPlan {
        worlds = List.copyOf(worlds);
        topologicalOrder = List.copyOf(topologicalOrder);
        commitOrder = List.copyOf(commitOrder);
        domainModes = List.copyOf(domainModes);
    }

    /** One step of the commit order. The position is the only order the commit layer may follow. */
    public record CommitStep(int position, String worldId, String domainId, String nodeKey,
                             long nodeId, int batchKind, int batchBound, boolean intent) {
    }

    /** The mode one (world, domain) pair runs in this tick, and the reason it holds it. */
    public record DomainMode(String worldId, String domainId, Mode mode, Reason reason) {
    }

    /** The mode of one pair: a conservative pair runs alone, a deferred pair runs nothing. */
    public enum Mode {
        ACTIVE,
        CONSERVATIVE,
        DEFERRED,
        DISABLED
    }

    public enum Reason {
        NONE,
        UNKNOWN_SITE,
        DECLARED_SERIAL,
        SHARE_EXHAUSTED,
        DEGRADED,
        NO_DECLARATION,
        WORLD_SET_CHANGED
    }

    public CommitStep step(int position) {
        for (CommitStep step : commitOrder) {
            if (step.position() == position) {
                return step;
            }
        }
        return null;
    }

    /** The commit steps of one pair, in plan order. */
    public List<CommitStep> stepsOf(String worldId, String domainId) {
        List<CommitStep> steps = new java.util.ArrayList<>();
        for (CommitStep step : commitOrder) {
            if (step.worldId().equals(worldId) && step.domainId().equals(domainId)) {
                steps.add(step);
            }
        }
        return steps;
    }

    public DomainMode modeOf(String worldId, String domainId) {
        for (DomainMode mode : domainModes) {
            if (mode.worldId().equals(worldId) && mode.domainId().equals(domainId)) {
                return mode;
            }
        }
        return null;
    }
}
