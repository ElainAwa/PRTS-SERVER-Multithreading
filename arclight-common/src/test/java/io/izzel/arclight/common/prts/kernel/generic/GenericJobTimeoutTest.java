/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenericJobTimeoutTest {

    @Test
    void timeoutCancelAndBackpressure() throws Exception {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-",
            Thread.NORM_PRIORITY, 1, 4), 1, readings, arena);
        CountDownLatch release = new CountDownLatch(1);
        try {
            WorkerPool.GenericHandle blocked = pool.submitGeneric(token -> {
                release.await(5, TimeUnit.SECONDS);
            }, new CancelToken(1L));
            assertNotNull(blocked);
            assertThrows(TimeoutException.class, () -> blocked.await(20, TimeUnit.MILLISECONDS));
            assertNull(pool.submitGeneric(token -> { }, new CancelToken(2L)),
                "queue cap must reject the second generic job");
            blocked.cancel();
            release.countDown();
        } finally {
            release.countDown();
            pool.shutdown(true, true, 2000L);
        }
    }
}
