/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;

import java.util.List;

/** Dormant internal AI plan-freeze hook: no producer means the entity plan is byte-for-byte
 * unchanged. The producer is set only by internal wiring/tests; no formal key is read. */
final class AiPlanFreezeHook {

    /** One freeze's entity hand-in: the declarations its rows ask for and the artefacts that make
     * those declarations identifiable, both taken from the same due window. */
    record Capture(List<JobDeclaration> declarations,
                   List<FrozenDeclarations.Candidate> candidates) {

        static Capture empty() {
            return new Capture(List.of(), List.of());
        }
    }

    @FunctionalInterface
    interface Producer {
        Capture capture(List<String> worlds, long planSequence);
    }

    private static volatile Producer producer;

    private AiPlanFreezeHook() {
    }

    static void setProducer(Producer value) {
        producer = value;
    }

    static Capture freeze(JobIntake intake, List<String> worlds, long planSequence) {
        Producer current = producer;
        if (current == null || intake == null) {
            AiDiagnostics.freezeSkipped("producer_null");
            return Capture.empty();
        }
        AiDiagnostics.freezeHookCall();
        Capture capture = current.capture(worlds, planSequence);
        if (capture == null) {
            return Capture.empty();
        }
        AiDiagnostics.aiNodes(capture.declarations().size());
        for (JobDeclaration declaration : capture.declarations()) {
            intake.submit(declaration);
        }
        return capture;
    }
}
