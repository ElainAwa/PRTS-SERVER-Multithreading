/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only B2 workload: a controlled declaration change makes the real bounded recompute
 * rebuild, and the B2 branch reuses that previous graph instead of rebuilding it. */
class P3ACC34B2WorkloadTest {

    private static final String WORLD = "world-a";
    private static final int CAP = 64;

    private static JobDeclaration declaration(String key, int priority) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, List.of(), priority,
            key, "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4);
    }

    private static List<JobDeclaration> declarations(int changedPriority) {
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", 0));
        declarations.add(declaration("a/1", changedPriority));
        return declarations;
    }

    private static ShareTable table(long tick) {
        return new SharePlanner().plan(List.of(WORLD), tick, Map.of());
    }

    private static TickPlanStore.Control control() {
        return TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal");
    }

    private static TickPlanPlanner.Input input(List<JobDeclaration> declarations, JobGraph previous) {
        return new TickPlanPlanner.Input(10L, 2L, 1L, List.of(WORLD), List.of("entity"),
            control(), declarations, table(10L), CAP, TickPlanStore.Feedback.none(), previous);
    }

    @Test
    void aRealDeclarationChangeRebuildsAndTheB2BranchReusesThePreviousGraph() {
        List<JobDeclaration> before = declarations(0);
        List<JobDeclaration> changed = declarations(5);
        JobGraph previous = JobGraphBuilder.freeze(before, 9L, 1L, 1L, CAP).graph();

        JobGraphBuilder.Recompute rebuild = JobGraphBuilder.recompute(
            new JobGraphBuilder.RecomputeRequest(previous, changed, 10L, 2L, 1L, CAP,
                JobGraphBuilder.scanWorkBudget(CAP), JobGraphBuilder.fullWorkBudget(CAP)));
        assertTrue(rebuild.ok());
        assertEquals(JobGraphBuilder.RecomputeMode.FULL_FALLBACK, rebuild.mode());
        assertTrue(rebuild.dirtyNodes() > 0);

        TickPlanPlanner.Result normal = TickPlanPlanner.plan(input(changed, previous));
        TickPlanPlanner.Result postponed = TickPlanPlanner.plan(input(changed, previous), true);
        assertTrue(normal.ok() && postponed.ok());
        assertNotEquals(normal.plan().graph().nodes(), previous.nodes(),
            "the changed declarations rebuild a different graph on the normal path");
        assertEquals(previous.nodes(), postponed.plan().graph().nodes(),
            "the B2 branch reuses the previous graph instead of rebuilding");
        assertEquals(JobGraphBuilder.RecomputeMode.REUSED, postponed.recompute().mode());
    }
}
