/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The takeover settlement of the write-back leg: one intent per committed batch, in the frozen order,
 * tagged with the batch it carries, drained by the commit segment and read back from the world
 * afterwards. The opt-in switch is what makes a batch take that path; the default settlement is
 * compute-only and is pinned in {@link DispatchAgreementTest}.
 */
class DispatchWriteBackTest {

    private static final long TICK = 100L;
    private static final String WORLD = "minecraft:overworld";

    @Test
    void everyCommittedBatchBecomesOneIntentTaggedWithItsBatchInFrozenOrder() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        List<WriteIntent> applied = new ArrayList<>();
        AtomicInteger handles = new AtomicInteger();
        intents.bindPayload(intent -> {
            applied.add(intent);
            return IntentPayload.Outcome.APPLIED;
        });
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                store.put(handle, write);
                return handle;
            }, store::remove, world -> 7L, readings, () -> true);
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
            assertEquals(plan.taskCount(), intents.enqueuedCount());
            assertEquals(plan.taskCount(), readings.writeBackEnqueued());
            assertEquals(plan.taskCount(), intents.depth(WORLD));
            assertEquals(plan.taskCount(), store.size());
            assertEquals(0L, readings.writeBackRefused());

            CommitSegment segment = new CommitSegment(intents, () -> true, () -> 64);
            segment.bindOwnerThread(Thread.currentThread());
            CommitSegment.Pass walk = segment.run(TICK + 1);
            assertEquals(plan.taskCount(), walk.steps());
            assertEquals(plan.taskCount(), segment.cursor());
            assertEquals(0L, intents.orderViolationCount());
            assertEquals(plan.taskCount(), applied.size());
            for (int index = 0; index < plan.taskCount(); index++) {
                long batchId = plan.tasks().get(index).batchId();
                assertEquals(index, applied.get(index).frozenOrder(),
                    "the frozen order did not follow the plan");
                assertEquals(DispatchWriteBack.SITE_PREFIX + ":" + batchId,
                    applied.get(index).siteId(), "a write did not carry its batch");
                assertEquals(WORLD, applied.get(index).dstWorldId());
                assertEquals(7L, applied.get(index).worldEpoch());
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void aBatchTheWorkerDidNotAnswerIsWrittenBackInItsOwnPosition() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                store.put(handle, write);
                return handle;
            }, store::remove, world -> 1L, readings, () -> true);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(4)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 4);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                sleep(200L);
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 1_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", writeBack);

            assertTrue(frame.cancelled() > 0, "no batch was cancelled for this leg");
            assertEquals(plan.taskCount(), frame.committed());
            assertEquals(plan.taskCount(), readings.tasksOnMain());
            assertEquals(plan.taskCount(), intents.enqueuedCount(),
                "a batch the tick thread redid did not produce its write");
            List<Long> written = new ArrayList<>();
            for (PrtsWorldWriteTaps.DeferredWrite write : store.values()) {
                assertTrue(write instanceof BatchWriteBack);
                List<StateHasher.Slice> rows = ((BatchWriteBack) write).rows();
                assertFalse(rows.isEmpty(), "a write-back carried no row");
                written.add(rows.get(0).batchId());
            }
            for (int index = 0; index < plan.taskCount(); index++) {
                assertEquals(plan.tasks().get(index).batchId(), written.get(index),
                    "a write-back left the position of its batch");
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void aChannelAtItsDepthRefusesTheWriteBackAndCountsIt() {
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        IntentQueue intents = new IntentQueue(() -> 1, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                store.put(handle, write);
                return handle;
            }, store::remove, world -> 1L, readings, () -> true);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view(8)), 4, 1L);
        WorkerPool pool = pool(readings, arena, 8);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), "entity", writeBack);

            assertEquals(1L, intents.enqueuedCount());
            assertEquals(1, intents.depth(WORLD));
            assertEquals(plan.taskCount() - 1, readings.writeBackRefused());
            assertEquals(1, store.size(), "a refused payload was not forgotten");
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
    }

    @Test
    void aWorldThatIsNotLoadedCountsEveryRowAsGoneAndComparesNothing() {
        DispatchReadings readings = new DispatchReadings();
        IntentQueue intents = new IntentQueue(() -> 8, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                store.put(prefix + ":1", write);
                return prefix + ":1";
            }, store::remove, world -> 0L, readings, () -> true);
        List<StateHasher.Slice> row = List.of(new StateHasher.Slice("no-such-world", "r0.0", 1L,
            5L, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L));

        DispatchWriteBack.ReadBack readBack = writeBack.readBack(row, HashWhitelist.bitexact(),
            "entity", TICK);

        assertEquals(0, readBack.rows());
        assertEquals(1, readBack.gone());
        assertTrue(readBack.equal());
        assertEquals(1L, readings.readBackPairs());
        assertEquals(1L, readings.readBackEqual());
        assertEquals(1L, readings.readBackGone());
        assertFalse(readings.hashInconsistent() > 0L);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
