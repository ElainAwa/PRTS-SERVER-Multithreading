/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Component test: existing ownership lease/executor path carries the test-only AI read-set. */
class MobAiOwnershipLeaseTest {

    private static MobAiTickModel.ReadSet readSet(boolean valid) {
        return new MobAiTickModel.ReadSet(9L, List.of("block"), List.of("entity"), List.of("poi"),
            List.of("sensor"), List.of("stroll"), "stroll", valid);
    }

    private static OwnershipLease.EntityCapability capability() {
        return new OwnershipLease.EntityCapability(1, 2L, 3L, 4, true, true, (byte) 0,
            0.0, 0.0, 0.0, 5L, 0);
    }

    @Test
    void workerComputesOnceAndHostAppliesOnce() {
        MobAiTickModel model = new MobAiTickModel();
        TickState captured = new TickState();
        model.captureReadSet(readSet(true), captured);
        OwnershipLease lease = new OwnershipLease(1L, 1, 1L);
        assertEquals(0, lease.issue(capability(), model, captured));
        lease.publish(Runnable::run, 1);
        assertEquals(OwnershipLease.SETTLED, lease.state(0));
        assertEquals(1L, model.computeRows());
        model.apply(null, lease.answer(0));
        model.noteApplied();
        assertEquals(1L, model.appliedCount());
        lease.consume(0);
        assertEquals(OwnershipLease.CONSUMED, lease.state(0));
    }

    @Test
    void invalidReadSetWorkerFailsAndHostDoesNotApply() {
        MobAiTickModel model = new MobAiTickModel();
        TickState captured = new TickState();
        model.captureReadSet(readSet(false), captured);
        OwnershipLease lease = new OwnershipLease(1L, 1, 1L);
        lease.issue(capability(), model, captured);
        lease.publish(Runnable::run, 1);
        assertEquals(OwnershipLease.FAILED, lease.state(0));
        assertEquals(0L, model.computeRows());
        model.apply(null, lease.answer(0));
        assertEquals(0L, model.appliedCount());
    }

    @Test
    void copiedAnswerCarriesFrozenReadSet() {
        MobAiTickModel model = new MobAiTickModel();
        TickState captured = new TickState();
        model.captureReadSet(readSet(true), captured);
        TickState workerCopy = new TickState();
        workerCopy.copyFrom(captured);
        assertEquals(true, model.compute(workerCopy));
        assertEquals(null, captured.modelAnswer, "captured state must not carry a worker answer");
    }

    @Test
    void twoRowsComputeConcurrentlyWithoutSharedSidecar() throws Exception {
        MobAiTickModel model = new MobAiTickModel();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TickState first = new TickState();
            TickState second = new TickState();
            model.captureReadSet(readSet(true), first);
            model.captureReadSet(readSet(true), second);
            Future<Boolean> a = pool.submit(() -> model.compute(first));
            Future<Boolean> b = pool.submit(() -> model.compute(second));
            assertEquals(true, a.get());
            assertEquals(true, b.get());
            assertEquals(2L, model.computeRows());
        } finally {
            pool.shutdownNow();
        }
    }
}
