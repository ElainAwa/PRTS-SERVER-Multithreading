/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** The self checks of the entity domain: it drives its own pipeline on scratch objects, so the
 * kernel can run them without holding a single type of this domain. */
final class EntityDomainSelfCheck {

    private static final String DOMAIN_ID = EntityDomain.ID;

    private EntityDomainSelfCheck() {
    }

    /** Drives the dispatch pipeline of this domain: the frozen plan, the worker answer, the merge,
     * the duplicate commit and the two failure arms. */
    static List<String> run(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        EntityCandidateView view = fixture();
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(view), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            8, 4), 1, readings, arena);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), DOMAIN_ID, null);
            lines.add("selftest.dispatch_tasks=" + plan.taskCount());
            lines.add("selftest.dispatch_worker_exec=" + readings.execByThread("prts-worker-0"));
            lines.add("selftest.dispatch_executed=" + readings.executed());
            lines.add("selftest.dispatch_committed=" + frame.committed());
            lines.add("selftest.dispatch_closure=" + (frame.closureOk() ? "ok" : "broken"));
            lines.add("selftest.dispatch_hash_equal=" + (frame.hashEqual() ? 1 : 0));
            lines.add("selftest.dispatch_pins=" + (arena.pinPairsHold() ? "ok" : "broken"));
            lines.add("selftest.dispatch_thread_class=" + pool.threadClasses().get(0).threadClass());
            if (frame == null || frame.committed() != plan.taskCount()) {
                failures.add("the dispatched batches were not committed exactly once");
            }
            if (!frame.closureOk()) {
                failures.add("the dispatch accounting does not close");
            }
            if (readings.execByThread("prts-worker-0") <= 0) {
                failures.add("no batch ran on a worker thread");
            }
            if (!frame.hashEqual()) {
                failures.add("the two arms of the state hash did not agree");
            }
            if (!arena.pinPairsHold()) {
                failures.add("an arena slot was not released");
            }
            long firstBatch = plan.tasks().get(0).batchId() + 1000L;
            ledger.register(firstBatch);
            boolean firstCommit = ledger.markCommitted(firstBatch);
            boolean secondCommit = ledger.markCommitted(firstBatch);
            lines.add("selftest.dispatch_duplicate_refused=" + (firstCommit && !secondCommit ? 1 : 0));
            if (!firstCommit || secondCommit) {
                failures.add("a second commit of one batch was not refused");
            }
            lines.addAll(failureMatrix(failures, tick));
            lines.addAll(writeBackMatrix(failures, tick));
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        return lines;
    }

    private static List<String> writeBackMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
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
        DispatchWriteBack writeBack = new DispatchWriteBack(intents, (prefix, write) -> {
            String handle = prefix + ":" + handles.incrementAndGet();
            store.put(handle, write);
            return handle;
        }, store::remove, world -> 1L, readings, () -> true);
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(fixture()), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            8, 4), 1, readings, arena);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), DOMAIN_ID, writeBack);
            CommitSegment segment = new CommitSegment(intents, () -> true, () -> 64);
            segment.bindOwnerThread(Thread.currentThread());
            CommitSegment.Pass walk = segment.run(tick + 1);
            boolean tagged = applied.size() == plan.taskCount();
            for (int index = 0; index < applied.size() && tagged; index++) {
                tagged = applied.get(index).siteId().equals(DispatchWriteBack.SITE_PREFIX + ":"
                    + plan.tasks().get(index).batchId());
            }
            lines.add("selftest.dispatch_writeback_intents=" + intents.enqueuedCount());
            lines.add("selftest.dispatch_writeback_tagged=" + (tagged ? 1 : 0));
            lines.add("selftest.dispatch_writeback_cursor=" + segment.cursor());
            lines.add("selftest.dispatch_writeback_order_violations="
                + intents.orderViolationCount());
            if (intents.enqueuedCount() != plan.taskCount() || walk.steps() != plan.taskCount()) {
                failures.add("a committed batch did not become exactly one write");
            }
            if (!tagged) {
                failures.add("a write did not carry the identity of its batch in the frozen order");
            }
            if (segment.cursor() != plan.taskCount() || intents.orderViolationCount() != 0L) {
                failures.add("the commit did not consume the frozen order of the write-backs");
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        DispatchReadings refused = new DispatchReadings();
        IntentQueue shallow = new IntentQueue(() -> 1, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> shallowStore = new LinkedHashMap<>();
        AtomicInteger shallowHandles = new AtomicInteger();
        DispatchWriteBack shallowWriteBack = new DispatchWriteBack(shallow, (prefix, write) -> {
            String handle = prefix + ":" + shallowHandles.incrementAndGet();
            shallowStore.put(handle, write);
            return handle;
        }, shallowStore::remove, world -> 1L, refused, () -> true);
        List<StateHasher.Slice> rows = List.of(slice(1.0));
        shallowWriteBack.enqueue(batchOf(1L, rows.get(0).regionId(), rows), rows);
        shallowWriteBack.enqueue(batchOf(2L, rows.get(0).regionId(), rows), rows);
        lines.add("selftest.dispatch_writeback_refused=" + refused.writeBackRefused());
        if (refused.writeBackRefused() != 1L || shallowStore.size() != 1) {
            failures.add("a write-back refused at the channel depth was not counted and forgotten");
        }
        // The default settlement is compute-only: the same rows are read back against the world and
        // nothing is handed to the channel, which is what keeps the domain from owning the state.
        DispatchReadings computeOnly = new DispatchReadings();
        IntentQueue idle = new IntentQueue(() -> 64, () -> 1);
        DispatchWriteBack computeLeg = new DispatchWriteBack(idle, (prefix, write) -> prefix,
            handle -> { }, world -> 1L, computeOnly, () -> false);
        computeLeg.settle(batchOf(9L, rows.get(0).regionId(), rows), rows);
        lines.add("selftest.dispatch_settle_landed=" + idle.enqueuedCount());
        lines.add("selftest.dispatch_settle_readback=" + computeOnly.readBackPairs());
        if (idle.enqueuedCount() != 0L || computeOnly.readBackPairs() != 1L) {
            failures.add("the compute-only settlement landed a value or skipped its read back");
        }
        return lines;
    }

    private static WorkBatch batchOf(long batchId, String regionId, List<StateHasher.Slice> rows) {
        WorkTask task = new WorkTask(batchId, "dispatch-selftest", regionId, batchId, 0, 1, 1L, 1L, 0,
            regionId);
        return new WorkBatch(batchId, task, 1L, fixture());
    }

    private static List<String> failureMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        EntityCandidateView slow = fixture("dispatch-late");
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(slow), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            1, 4), 1, readings, arena);
        try {
            DispatchPass first = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            WorkPlan extra = WorkPlan.freeze(tick, 1L, List.of(fixture("dispatch-full")), 4, 2L);
            DispatchPass refused = DispatchPass.dispatch(extra, pool, EntityIntegrator.INSTANCE,
                arena, readings, ledger);
            lines.add("selftest.dispatch_backpressure=" + (refused.entries().get(0).handle() == null
                ? 1 : 0));
            if (refused.entries().get(0).handle() != null) {
                failures.add("a full worker queue did not fall back");
            }
            first.awaitAll(System.nanoTime() + 1_000_000L);
            long dropsBefore = readings.lateResultDropped();
            joinQuietly(180L);
            lines.add("selftest.dispatch_late_dropped="
                + (readings.lateResultDropped() > dropsBefore ? 1 : 0));
            if (readings.lateResultDropped() <= dropsBefore) {
                failures.add("a result that arrived after the deadline was not dropped and counted");
            }
            lines.add("selftest.dispatch_timeouts=" + readings.timeouts());
            if (readings.timeouts() <= 0) {
                failures.add("the deadline did not cancel a batch that did not answer");
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        List<StateHasher.Slice> one = List.of(slice(1.0));
        List<StateHasher.Slice> same = List.of(slice(1.0));
        List<StateHasher.Slice> other = List.of(slice(1.0000000000000002));
        boolean equal = StateHasher.hash(DOMAIN_ID, tick, one, HashWhitelist.bitexact()).value()
            == StateHasher.hash(DOMAIN_ID, tick, same, HashWhitelist.bitexact()).value();
        boolean different = StateHasher.hash(DOMAIN_ID, tick, one, HashWhitelist.bitexact()).value()
            != StateHasher.hash(DOMAIN_ID, tick, other, HashWhitelist.bitexact()).value();
        boolean empty = !StateHasher.hash(DOMAIN_ID, tick, List.of(), HashWhitelist.bitexact())
            .comparable();
        lines.add("selftest.dispatch_hash_exact=" + (equal && different ? 1 : 0));
        lines.add("selftest.dispatch_hash_empty_refused=" + (empty ? 1 : 0));
        if (!equal || !different) {
            failures.add("the bit-exact hash did not agree and disagree as required");
        }
        if (!empty) {
            failures.add("an empty range produced a hash instead of an error");
        }
        return lines;
    }

    private static StateHasher.Slice slice(double x) {
        return new StateHasher.Slice("world", "r0.0", 1L, 1L, x, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0L, 0L, 0L);
    }

    private static EntityCandidateView fixture() {
        return fixture("dispatch-selftest");
    }

    private static EntityCandidateView fixture(String worldId) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, 1L);
        for (int i = 0; i < 8; i++) {
            builder.add(i, 0, 0, i, 64.0, 0.0, 0.1, 0.0, 0.0, 0.01, 0.0, 0L);
        }
        return builder.build();
    }

    private static void joinQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
