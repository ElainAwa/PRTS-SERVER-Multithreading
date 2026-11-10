/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanPlanner;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;

import java.util.List;

/** Internal TickPlan producer for AI jobs; the plan envelope remains the kernel's. */
public final class AiTickPlanProducer {

    private AiTickPlanProducer() {
    }

    public static TickPlanPlanner.Result plan(long tickIndex, long planSequence,
                                               long worldSetGeneration, List<String> worlds,
                                               List<JobDeclaration> declarations,
                                               TickPlanStore.Control control, ShareTable shareTable,
                                               int queueCap) {
        return TickPlanPlanner.plan(new TickPlanPlanner.Input(tickIndex, planSequence,
            worldSetGeneration, worlds, List.of("ai"), control, declarations, shareTable, queueCap,
            TickPlanStore.Feedback.none()));
    }

    public static TickPlan planOrNull(TickPlanPlanner.Result result) {
        return result == null ? null : result.plan();
    }
}
