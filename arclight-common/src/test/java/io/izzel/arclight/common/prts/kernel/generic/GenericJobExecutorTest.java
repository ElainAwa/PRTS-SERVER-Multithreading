/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelDomain;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GenericJobExecutorTest {

    private static KernelDomain domain(String id) {
        return new KernelDomain() {
            public String id() { return id; }
            public void tick(long tickIndex) { }
            public void shutdown() { }
            public void reset() { }
            public void readings(DomainReadings readings) { }
            public java.util.List<String> selfCheck(java.util.List<String> failures, long tick) { return java.util.List.of(); }
        };
    }

    private static JobDeclaration declaration(String key, String world, String domain) {
        return new JobDeclaration(key, 1L, world, domain, 0, List.of(), 0, "affinity", "cancel",
            List.of(), List.of(), ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8, List.of());
    }

    @Test
    void twoDomainsRegisterAndExecuteWithoutEntityTypes() throws Exception {
        DomainRegistry registry = new DomainRegistry();
        assertTrue(registry.register(domain("entity")));
        assertTrue(registry.register(domain("example")));
        assertEquals(2, registry.size());
        var freeze = JobGraphBuilder.freezeDiagnostic(
            List.of(declaration("entity/a", "world", "entity"),
                declaration("example/a", "world", "example")), 1L, 1L, 1L, 8);
        assertEquals(true, freeze.ok());
        AtomicInteger ran = new AtomicInteger();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-",
            Thread.NORM_PRIORITY, 8, 4), 1, new io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings(),
            new io.izzel.arclight.common.prts.kernel.arena.ArenaLedger());
        try {
            GenericJobExecutor executor = new GenericJobExecutor(pool);
            var handles = executor.execute(freeze.graph(), Map.of(
                "entity/a", ran::incrementAndGet,
                "example/a", ran::incrementAndGet));
            assertEquals(2, handles.size());
            for (var handle : handles) handle.await(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, ran.get());
        } finally {
            pool.shutdown(true, true, 2000L);
        }
    }
}
