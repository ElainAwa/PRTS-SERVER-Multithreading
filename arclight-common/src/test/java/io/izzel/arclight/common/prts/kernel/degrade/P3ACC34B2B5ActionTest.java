/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red/new-green source contract for the authorized B2/B5 triggered TickPlan actions. */
class P3ACC34B2B5ActionTest {

    @Test
    void b2AndB5HaveRealTriggeredActionsNotInjection() throws Exception {
        String kernel = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/KernelModule.java"));
        assertTrue(kernel.contains("b2PostponeWanted"));
        assertTrue(kernel.contains("b5ShrinkWanted"));
        assertTrue(kernel.contains("noteEffective(DegradeLevel.B2)"));
        assertTrue(kernel.contains("noteEffective(DegradeLevel.B5)"));
        assertTrue(kernel.contains("noteReturned(DegradeLevel.B2)"));
        assertTrue(kernel.contains("noteReturned(DegradeLevel.B5)"));
        assertTrue(kernel.contains("b5ActionPending"));
        String planner = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/plan/TickPlanPlanner.java"));
        assertTrue(planner.contains("plan(Input input, boolean postponeRebuild)"));
        assertTrue(planner.contains("RecomputeMode.REUSED"));
    }
}
