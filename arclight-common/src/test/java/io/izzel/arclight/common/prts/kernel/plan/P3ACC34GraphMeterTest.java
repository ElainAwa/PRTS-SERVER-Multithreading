/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red/new-green for the real GRAPH self timer: one sample per actual bounded recompute,
 * gated by self-timers/share-table, and the self check no longer forces graph to zero. */
class P3ACC34GraphMeterTest {

    @Test
    void theKernelMetersTheRealRecomputeUnderTheTwoSwitches() throws Exception {
        String kernel = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/KernelModule.java"));
        assertTrue(kernel.contains("KernelSettings.selfTimers() && KernelSettings.shareTable()"));
        assertTrue(kernel.contains("SelfTimers.note(SelfClass.GRAPH"));
        assertTrue(kernel.contains("result.recompute() != null"));
        String planner = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/plan/TickPlanPlanner.java"));
        assertFalse(planner.contains("nanoTime"), "the planning package must not read a clock");
    }

    @Test
    void theSelfCheckNoLongerRequiresGraphToBeZero() throws Exception {
        String check = Files.readString(Path.of(
            "src/main/java/io/izzel/arclight/common/prts/kernel/observe/KernelSelfCheck.java"));
        assertTrue(check.contains("row(ShareClass.GRAPH).usedMs() < 0.0"));
    }
}
