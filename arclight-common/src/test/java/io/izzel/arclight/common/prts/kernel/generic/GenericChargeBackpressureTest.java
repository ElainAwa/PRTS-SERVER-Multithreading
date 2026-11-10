/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericChargeBackpressureTest {

    @Test
    void exhaustedBudgetRejectsBeforeSubmit() {
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(
            List.of(new JobDeclaration("a/0", 1L, "w", "example", 0, List.of(), 0, "a", "c",
                List.of(), List.of(), ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8,
                List.of())), 1L, 1L, 1L, 8);
        JobExecutionContext exhausted = new JobExecutionContext(Set.of("example"),
            Set.of("example"), Set.of(), 1L, 0.0);
        assertTrue(!exhausted.charge(1.0));
        assertTrue(exhausted.exhausted());
        GenericJobExecutor executor = new GenericJobExecutor(null, exhausted);
        assertThrows(RejectedExecutionException.class,
            () -> executor.execute(freeze.graph(), Map.of("a/0", () -> { })));
    }
}
