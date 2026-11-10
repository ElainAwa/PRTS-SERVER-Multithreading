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
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJobContextTest {

    private static JobDeclaration declaration(String key) {
        JobDeclaration.DomainRef read = new JobDeclaration.DomainRef("world", "entity", 0);
        JobDeclaration.DomainRef write = new JobDeclaration.DomainRef("world", "entity", 0);
        return new JobDeclaration(key, 1L, "world", "entity", 0, List.of(), 0, "affinity", "cancel",
            List.of(read), List.of(write), ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8,
            List.of());
    }

    private static WorkerPool pool(DispatchReadings readings, ArenaLedger arena) {
        return WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY, 8, 4),
            1, readings, arena);
    }

    @Test
    void declaredReadWriteAreAcceptedAndShareCharged() throws Exception {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        WorkerPool pool = pool(readings, arena);
        try {
            JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(
                List.of(declaration("entity/a")), 1L, 1L, 1L, 8);
            assertTrue(freeze.ok());
            JobExecutionContext context = new JobExecutionContext(Set.of("entity"), Set.of("entity"),
                Set.of("entity"), 1L, 10.0);
            GenericJobExecutor executor = new GenericJobExecutor(pool, context);
            var handles = executor.execute(freeze.graph(), Map.of("entity/a", () -> { }));
            assertEquals(1, handles.size());
            handles.get(0).await(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(context.chargedMs() >= 0.0);
        } finally {
            pool.shutdown(true, true, 2000L);
        }
    }

    @Test
    void undeclaredReadIsRejectedBeforeSubmit() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        WorkerPool pool = pool(readings, arena);
        try {
            JobGraphBuilder.Freeze freeze = JobGraphBuilder.freezeDiagnostic(
                List.of(declaration("entity/a")), 1L, 1L, 1L, 8);
            JobExecutionContext context = new JobExecutionContext(Set.of(), Set.of("entity"),
                Set.of("entity"), 1L, 10.0);
            GenericJobExecutor executor = new GenericJobExecutor(pool, context);
            assertThrows(RejectedExecutionException.class,
                () -> executor.execute(freeze.graph(), Map.of("entity/a", () -> { })));
        } finally {
            pool.shutdown(true, true, 2000L);
        }
    }
}
