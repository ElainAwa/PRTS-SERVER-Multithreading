/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;

import java.util.List;

/** Dormant internal AI plan-freeze hook: no producer means the entity plan is byte-for-byte
 * unchanged. The producer is set only by internal wiring/tests; no formal key is read. */
final class AiPlanFreezeHook {

    @FunctionalInterface
    interface Producer {
        List<JobDeclaration> declarations(List<String> worlds, long planSequence);
    }

    private static volatile Producer producer;

    private AiPlanFreezeHook() {
    }

    static void setProducer(Producer value) {
        producer = value;
    }

    static void freeze(JobIntake intake, List<String> worlds, long planSequence) {
        Producer current = producer;
        if (current == null || intake == null) {
            AiDiagnostics.freezeSkipped("producer_null");
            return;
        }
        AiDiagnostics.freezeHookCall();
        var declarations = current.declarations(worlds, planSequence);
        AiDiagnostics.aiNodes(declarations.size());
        for (JobDeclaration declaration : declarations) {
            intake.submit(declaration);
        }
    }
}
