/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobMeteringTest {

    private static JobDeclaration declaration(String key, String domain) {
        return new JobDeclaration(key, 1L, "w", domain, 0, List.of(), 0, "a", "c",
            List.of(new JobDeclaration.DomainRef("w", domain, 0)),
            List.of(new JobDeclaration.DomainRef("w", domain, 0)), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 8, List.of());
    }

    @Test
    void twoDomainsChargeOncePerCompletedJob() throws Exception {
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-",
            Thread.NORM_PRIORITY, 8, 4), 1, new DispatchReadings(), new ArenaLedger());
        try {
            JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(
                List.of(declaration("entity/a", "entity"), declaration("example/a", "example")),
                1L, 1L, 1L, 8);
            JobMetering metering = new JobMetering();
            GenericJobExecutor executor = new GenericJobExecutor(pool,
                new JobExecutionContext(Set.of("entity", "example"), Set.of("entity", "example"),
                    Set.of(), 1L, 100.0), metering);
            var handles = executor.execute(freeze.graph(), Map.of(
                "entity/a", () -> { }, "example/a", () -> { }));
            for (var handle : handles) handle.await(5, TimeUnit.SECONDS);
            Map<String, java.util.EnumMap<ShareClass, Double>> used = metering.usedMs();
            assertEquals(2, used.get("w").size());
            assertTrue(used.get("w").get(ShareClass.ENTITY) >= 0.0);
            assertTrue(used.get("w").get(ShareClass.OTHER) >= 0.0);
        } finally {
            pool.shutdown(true, true, 2000L);
        }
    }
}
