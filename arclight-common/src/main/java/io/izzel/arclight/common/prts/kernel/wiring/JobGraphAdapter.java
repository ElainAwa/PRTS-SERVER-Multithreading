/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connects the entity domain to the job layer without moving the domain onto a new path: it declares
 * one job per frozen task, orders the next dispatch by the graph of the newest plan where the task
 * keys match, and books the cost of the job layer itself. Every call is made only while the job
 * switch is on; with it off the domain dispatches exactly the way it always did. */
public final class JobGraphAdapter {

    /** The domain the entity jobs are declared under, and the name its batch ledger knows. */
    public static final String DOMAIN = "entity";

    /** The affinity of one entity job is its region: rows of one region stay in one order. */
    private static final int BATCH_KIND_SINGLE_OWNER_TICK = 0;

    /** The holder the entity jobs ask their write right for, and the ticks one grant holds; declared
     * values. Every job of the domain asks for the same holder. The level is the one the plan keeps
     * the version of a declared domain at, so a token always names the version of its domain. */
    public static final String OWNER_SITE = "domain:" + DOMAIN;
    public static final int OWNER_HOLD_TICKS = 40;

    private final KernelModule module;
    private long ordered;
    private long unplanned;

    public JobGraphAdapter(KernelModule module) {
        this.module = module;
    }

    /** Hands the frozen tasks of one tick to the intake. They are what the next plan orders. */
    public void declare(WorkPlan plan) {
        if (plan == null) {
            return;
        }
        for (WorkTask task : plan.tasks()) {
            module.jobIntake().submit(declarationOf(task));
            module.noteJobDeclarations(1);
        }
    }

    /** The order this tick's tasks are dispatched in: the plan's order for the keys it knows, then
     * the tasks it does not know in the order the freeze gave them. Tasks without a planned order are
     * counted, so a plan that fell behind its domain is readable instead of silently ignored. */
    public List<WorkTask> order(WorkPlan plan) {
        if (plan == null || plan.tasks().isEmpty()) {
            return plan == null ? List.of() : plan.tasks();
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        TickPlan latest = module.plans().latest();
        if (latest != null && latest.graph() != null) {
            for (JobGraph.Node node : latest.graph().nodes()) {
                positions.put(node.key(), node.position());
            }
        }
        List<WorkTask> known = new ArrayList<>();
        List<WorkTask> unknown = new ArrayList<>();
        for (WorkTask task : plan.tasks()) {
            if (positions.containsKey(keyOf(task))) {
                known.add(task);
            } else {
                unknown.add(task);
            }
        }
        known.sort((left, right) -> {
            int byPosition = Integer.compare(positions.get(keyOf(left)), positions.get(keyOf(right)));
            return byPosition != 0 ? byPosition : keyOf(left).compareTo(keyOf(right));
        });
        ordered += known.size();
        unplanned += unknown.size();
        List<WorkTask> result = new ArrayList<>(plan.tasks().size());
        result.addAll(known);
        result.addAll(unknown);
        return result;
    }

    /** Meters the cost of the job layer of one tick. The cost is the layer's own work - the graph and
     * the hand-out - and is booked under the class the jobs were declared with, so the share table
     * sees the layer that runs on the tick thread. */
    public void meter(String worldId, long nanos) {
        if (!KernelSettings.jobGraph() || nanos <= 0L) {
            return;
        }
        module.jobMeter().note(SelfClass.ENTITY, worldId, "job-graph", nanos);
    }

    /** The declaration of one frozen task, with the write right it asks to hold; declared, and only
     * granted if the freeze of the plan grants it. */
    public static JobDeclaration declarationOf(WorkTask task) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(task.worldId(), DOMAIN, 0);
        return new JobDeclaration(keyOf(task), task.batchId(), task.worldId(), DOMAIN, 0, List.of(),
            task.entityCount(), task.regionId(), task.cancelScope(), List.of(ref), List.of(ref),
            ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, BATCH_KIND_SINGLE_OWNER_TICK,
            Math.max(1, task.entityCount()), List.of(ownerDemandOf(task)));
    }

    private static JobDeclaration.OwnerDemand ownerDemandOf(WorkTask task) {
        return new JobDeclaration.OwnerDemand(task.worldId(), WriteLevel.REGION, DOMAIN,
            HolderKind.REGISTERED, OWNER_SITE, OWNER_HOLD_TICKS);
    }

    /** The key a task is declared and matched by: its world and its region. */
    public static String keyOf(WorkTask task) {
        return DOMAIN + "/" + task.worldId() + "/" + task.regionId();
    }

    public long orderedPlanned() {
        return ordered;
    }

    public long orderedUnplanned() {
        return unplanned;
    }

    public void reset() {
        ordered = 0L;
        unplanned = 0L;
    }
}
