/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;

import java.util.List;

/** Builds the internal generic JobGraph of AI actors; no formal job keys are added. */
public final class AiJobGraph {

    private AiJobGraph() {
    }

    public static JobGraphBuilder.Freeze freeze(List<JobDeclaration> declarations, long tick,
                                                long planSequence, long lifecycle, int maxNodes) {
        return JobGraphBuilder.freezeDiagnostic(declarations, tick, planSequence, lifecycle, maxNodes);
    }
}
