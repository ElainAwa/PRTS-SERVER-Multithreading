/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compute-only settlement of the write-back leg: the default tier reads the world back in the
 * tick the frame belongs to, lands nothing, and leaves every state change with the host path. The
 * takeover switch is off by default, and turning it on is what hands a batch to the intent channel.
 */
class DispatchAgreementTest {

    private static final long TICK = 100L;
    private static final String WORLD = "minecraft:overworld";

    @Test
    void theTakeoverSwitchIsOffInTheDeclaredDefaults() {
        assertEquals(Boolean.FALSE, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .features().get(KernelSettings.DISPATCH_TAKEOVER));
        assertFalse(KernelSettings.dispatchTakeover(),
            "the compute-only tier is the declared default");
    }

    @Test
    void theComputeOnlySettlementLandsNothingAndReadsTheWorldBackInItsOwnTick() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        DispatchWriteBack writeBack = leg(intents, store, readings, () -> false);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);

            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", writeBack);

            assertTrue(plan.taskCount() > 1);
            assertEquals(plan.taskCount(), frame.committed());
            assertTrue(frame.closureOk());
            assertTrue(frame.hashEqual(), "the parallel and serial arms must still agree");
            assertEquals(readings.dispatched(), readings.executed(),
                "the whole frame has to come from the workers");
            assertEquals(0L, readings.tasksOnMain());
            assertTrue(readings.execByThread("prts-worker-0")
                + readings.execByThread("prts-worker-1") > 0, "no worker executed a batch");

            assertEquals(0L, intents.enqueuedCount(), "a compute-only settlement queued an intent");
            assertEquals(0, intents.depth(WORLD));
            assertEquals(0L, readings.writeBackEnqueued(),
                "a compute-only settlement handed rows to the channel");
            assertEquals(0, store.size());

            // No loaded world answers in a unit test, so every row is counted gone and none is
            // compared; what matters is that the read back ran in this merge and landed nothing.
            assertEquals(plan.taskCount(), readings.readBackPairs());
            assertEquals(readings.writeBackEnqueued(), readings.readBackRows());
            assertEquals(0L, readings.writeBackIdentical());
            assertEquals(0L, readings.writeBackKept());
            assertTrue(readings.readBackGone() > 0L);
            assertTrue(arena.pinPairsHold());
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void theSettlementFollowsTheTakeoverSwitchAndKeepsTheChannelEmptyOtherwise() {
        DispatchReadings readings = new DispatchReadings();
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        AtomicBoolean takeover = new AtomicBoolean(false);
        DispatchWriteBack writeBack = leg(intents, store, readings, takeover::get);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(4)), 4, 1L);
        WorkBatch batch = new WorkBatch(plan.tasks().get(0).batchId(), plan.tasks().get(0),
            plan.planEpoch(), view(4));
        StateHasher.Slice row = new StateHasher.Slice(WORLD, "r0.0", batch.batchId(), 0L,
            1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L);

        assertFalse(writeBack.takeover());
        assertFalse(writeBack.settle(batch, List.of(row)).landed(),
            "the default settlement landed a value");
        assertEquals(0L, intents.enqueuedCount());
        assertEquals(1L, readings.readBackPairs());

        takeover.set(true);
        assertTrue(writeBack.settle(batch, List.of(row)).landed(),
            "the takeover settlement left the batch");
        assertEquals(1L, intents.enqueuedCount());
        assertEquals(1, intents.depth(WORLD));
        assertEquals(1, store.size());
        assertEquals(1, readings.readBackPairs(), "the takeover settlement also read the world back");
    }

    private static DispatchWriteBack leg(IntentQueue intents,
                                         Map<String, PrtsWorldWriteTaps.DeferredWrite> store,
                                         DispatchReadings readings, java.util.function.BooleanSupplier
                                             takeover) {
        AtomicInteger handles = new AtomicInteger();
        return new DispatchWriteBack(intents, (prefix, write) -> {
            String handle = prefix + ":" + handles.incrementAndGet();
            store.put(handle, write);
            return handle;
        }, store::remove, world -> 1L, readings, takeover);
    }

    private static WorkerPool pool(DispatchReadings readings, ArenaLedger arena, int queueCap) {
        return WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY, queueCap,
            4), 1, readings, arena);
    }

    private static EntityCandidateView view(int entities) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        for (int i = 0; i < entities; i++) {
            builder.add(i, i * 4, 0, i, 64.0, 0.0, 0.25, 0.0, 0.5, 0.0, 0.0, 0L);
        }
        return builder.build();
    }
}
