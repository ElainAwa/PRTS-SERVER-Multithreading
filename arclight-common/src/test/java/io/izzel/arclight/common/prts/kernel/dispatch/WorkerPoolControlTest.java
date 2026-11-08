/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Control work uses the existing shard queue and the existing queue cap, never a second queue. */
class WorkerPoolControlTest {

    @Test
    void controlRunsOnExistingWorkerAndSharesQueueCap() throws Exception {
        DispatchReadings readings = new DispatchReadings();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-test-", Thread.NORM_PRIORITY,
            1, 1), 1, readings, new ArenaLedger());
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            assertTrue(pool.submitControl("minecraft:overworld", 1L, () -> {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(pool.submitControl("minecraft:overworld", 1L, () -> {
            }), "the shared queue cap must refuse a second control item");
            release.countDown();
        } finally {
            pool.shutdown(true, true, 200L);
        }
    }

    @Test
    void controlQuotaIsBoundedPerWorldAndPerTickWithBatchHeadroom() {
        DispatchReadings readings = new DispatchReadings();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-test-", Thread.NORM_PRIORITY,
            8, 1), 1, readings, new ArenaLedger());
        try {
            for (int index = 0; index < 2; index++) {
                assertTrue(pool.submitControl("world-a", 1L, () -> {
                }));
            }
            assertFalse(pool.submitControl("world-a", 1L, () -> {
            }), "one world cannot spend more than a quarter of the queue on controls");
            for (int index = 0; index < 2; index++) {
                assertTrue(pool.submitControl("world-b", 1L, () -> {
                }));
            }
            assertFalse(pool.submitControl("world-b", 1L, () -> {
            }), "all worlds together cannot spend more than half of the queue on controls");
            assertTrue(pool.submitControl("world-a", 2L, () -> {
            }), "a new tick starts a fresh quota");
        } finally {
            pool.shutdown(true, true, 500L);
        }
    }

    @Test
    void shutdownWaitsForAcceptedControl() {
        DispatchReadings readings = new DispatchReadings();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-test-", Thread.NORM_PRIORITY,
            4, 1), 1, readings, new ArenaLedger());
        AtomicBoolean ran = new AtomicBoolean();
        assertTrue(pool.submitControl("minecraft:overworld", 1L, () -> {
            ran.set(true);
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        WorkerPool.ShutdownReport report = pool.shutdown(true, true, 2_000L);
        assertTrue(ran.get(), "an accepted control task must run before shutdown returns");
        assertTrue(report.terminated());
        assertTrue(report.remainingInFlight() == 0);
    }
}