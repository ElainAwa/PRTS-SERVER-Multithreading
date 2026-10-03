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
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.BatchWriteBack;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
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
            }, store::remove, world -> 1L, readings, () -> true);
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
                assertEquals(1L, applied.get(index).worldEpoch(),
                    "the intent did not carry the generation the task froze");
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

    @Test
    void staleTaskCannotRetagWithCurrentWorldEpoch() {
        DispatchReadings readings = new DispatchReadings();
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of(WORLD));
        long frozen = epochs.epochOf(WORLD);
        EntityCandidateView view = view(4, WORLD, frozen);
        WorkPlan plan = WorkPlan.freeze(TICK, 1L, List.of(view), 4, 1L);
        WorkBatch batch = new WorkBatch(plan.tasks().get(0).batchId(), plan.tasks().get(0),
            plan.planEpoch(), view);
        List<StateHasher.Slice> rows = List.of(new StateHasher.Slice(WORLD, "r0.0",
            batch.batchId(), 1L, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L));
        IntentQueue intents = new IntentQueue(() -> 8, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                store.put(prefix + ":1", write);
                return prefix + ":1";
            }, store::remove, epochs::epochOf, readings, () -> true);

        // The world is unloaded and comes back under the same key: a new generation.
        epochs.observe(List.of("another-world"));
        epochs.observe(List.of(WORLD));
        assertTrue(epochs.epochOf(WORLD) > frozen, "a world that came back is a new generation");

        assertEquals(DispatchWriteBack.Settlement.REFUSED_WORLD_EPOCH,
            writeBack.settle(batch, rows), "a stale batch was retagged with the current generation");
        assertEquals(1L, readings.writeBackStale());
        assertEquals(0L, readings.writeBackEnqueued());
        assertEquals(0L, intents.enqueuedCount());
        assertTrue(store.isEmpty(), "a refused batch bound a payload");
    }

    @Test
    void anEnqueueRefusedAtTheDepthIsNotLanded() {
        DispatchReadings readings = new DispatchReadings();
        IntentQueue intents = new IntentQueue(() -> 1, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                store.put(handle, write);
                return handle;
            }, store::remove, world -> 1L, readings, () -> true);

        assertEquals(DispatchWriteBack.Settlement.ACCEPTED, writeBack.enqueue(batch(1L), rows(1L)));
        assertEquals(DispatchWriteBack.Settlement.REFUSED_QUEUE_CAP,
            writeBack.enqueue(batch(2L), rows(2L)),
            "a batch refused at the depth was reported as landed");
        assertEquals(1L, readings.writeBackRefused());
        assertEquals(1, store.size(), "a refused payload was not forgotten");
    }

    @Test
    void aBatchWhoseWorldIsGoneIsRefusedAndCounted() {
        DispatchReadings readings = new DispatchReadings();
        List<StateHasher.Slice> rows = List.of(new StateHasher.Slice("no-such-world", "r0.0", 1L,
            5L, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L));

        boolean landed = new BatchWriteBack(rows, readings).apply();

        assertFalse(landed, "a batch whose world is gone reported success");
        assertEquals(1L, readings.writeBackGone());
        assertEquals(0L, readings.writeBackNoRows(),
            "a gone world is a different terminal state from a world that lost its rows");
    }

    @Test
    void aBatchThatLandedNoRowIsCountedAsANoOp() {
        assertTrue(BatchWriteBack.noRowsLanded(true, false, 0, 0, 0));
        assertFalse(BatchWriteBack.noRowsLanded(true, false, 1, 0, 0));
        assertTrue(BatchWriteBack.noRowsLanded(true, true, 0, 0, 0));
        assertFalse(BatchWriteBack.noRowsLanded(true, true, 0, 1, 0));
        assertFalse(BatchWriteBack.noRowsLanded(true, true, 0, 0, 1));
        assertFalse(BatchWriteBack.noRowsLanded(false, false, 0, 0, 0));
    }

    private static WorkBatch batch(long batchId) {
        EntityCandidateView view = view(4);
        WorkTask task = new WorkTask(batchId, WORLD, "r0.0", batchId, 0, 4, 1L, 1L, 0, "r0.0");
        return new WorkBatch(batchId, task, 1L, view);
    }

    private static List<StateHasher.Slice> rows(long batchId) {
        return List.of(new StateHasher.Slice(WORLD, "r0.0", batchId, 1L, 1.0, 2.0, 3.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0L, 0L, 0L));
    }

    private static WorkerPool pool(DispatchReadings readings, ArenaLedger arena, int queueCap) {
        return WorkerPool.open(new WorkerPool.Spec(2, "prts-worker-", Thread.NORM_PRIORITY, queueCap,
            4), 1, readings, arena);
    }

    private static EntityCandidateView view(int entities) {
        return view(entities, WORLD, 1L);
    }

    private static EntityCandidateView view(int entities, String worldId, long worldEpoch) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, worldEpoch);
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
