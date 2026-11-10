/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real DispatchPass + WorkerPool: one frozen AI WorkPlan dispatched on the P0 pool. */
class AiSensorPoolDispatchTest {

    private static EntityCandidateView view() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("world", 1L);
        for (int i = 0; i < 8; i++) {
            builder.add(i, 0, 0, i, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        }
        return builder.build();
    }

    @Test
    void workPlanDispatchesOnRealWorkerPool() throws Exception {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY,
            8, 4), 1, readings, arena);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicLong rows = new AtomicLong();
        try {
            WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(view()), 16, 1L);
            DispatchPass pass = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                rows.addAndGet(batch.task().entityCount());
                ran.countDown();
                return 0L;
            }, arena, readings, ledger);
            assertEquals(1, pass.entries().size());
            assertTrue(ran.await(5, TimeUnit.SECONDS), "worker did not run the AI batch");
            assertEquals(8, rows.get());
        } finally {
            pool.shutdown(true, true, 2000L);
        }
    }
}
