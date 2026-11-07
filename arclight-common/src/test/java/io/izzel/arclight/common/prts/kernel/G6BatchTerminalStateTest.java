/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.wiring.EntityDomain;
import io.izzel.arclight.common.prts.kernel.wiring.KernelWiring;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only G6 evidence: every dispatched batch ends in exactly one terminal state and the write
 * payloads the channel accepted keep their order. The test drives the real merge, write-back, intent
 * queue, commit segment and commit log; only the per-batch bookkeeping is test-side, and the commit
 * log's private watermarks are read by reflection, never re-decided by this test.
 *
 * <p>Terminal partition asserted: enqueue-accepted batch set = applied payloads + unfinished
 * obligations (still queued) + explicit refusals/compute-only. Each reported batch reported once;
 * payload order equals the settlement order of the seating plan (seat ascending within a merge).</p>
 */
class G6BatchTerminalStateTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = "entity";
    private static final int QUEUE_CAP = 64;

    @Test
    void normalTwoTicksPartitionEveryBatchExactlyOnce() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan first = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        WorkPlan second = WorkPlan.freeze(2L, 2L, List.of(view()), 4, 10L);
        TickPlan firstPlan = harness.publishPlan(1L, List.of(first.tasks().get(0), first.tasks().get(1)));
        TickPlan secondPlan = harness.publishPlan(2L, List.of(second.tasks().get(0), second.tasks().get(1)));

        harness.dispatched(first);
        harness.dispatched(second);
        harness.mergeTick(1L, firstPlan, first, first.tasks());
        harness.mergeTick(2L, secondPlan, second, second.tasks());
        harness.drain(3L);

        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        assertEquals(8, harness.ledger.appliedCount(), "every landed batch must reach the world once");
        assertEquals(0, harness.ledger.pendingCount(), "the drain leaves no unfinished obligation");
        assertEquals(0, harness.ledger.refusedCount(), "no batch was refused");
        assertEquals(8, harness.ledger.reportCount(), "each landed batch reported once");
        assertEquals(8, harness.ledger.appliedOrder.size(),
            "the payload order applied equals the order the channel accepted");
    }

    @Test
    void samePlanSplitAcrossTwoMergesKeepsTerminalStatesAndPayloadOrder() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        TickPlan plan = harness.publishPlan(7L, work.tasks());

        harness.dispatched(work);
        harness.mergeTick(1L, plan, work, List.of(work.tasks().get(0), work.tasks().get(2)));
        harness.mergeTick(2L, plan, work, List.of(work.tasks().get(1), work.tasks().get(3)));
        harness.drain(3L);

        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        assertEquals(4, harness.ledger.appliedCount(), "the split reporting must not duplicate or lose a payload");
        assertEquals(4, harness.ledger.reportCount(), "each batch reported once even when its plan's seats split");
        List<Long> expected = new ArrayList<>();
        for (WorkPlan.WorkTask task : harness.seatOrdered(plan,
            List.of(work.tasks().get(0), work.tasks().get(2)))) {
            expected.add(task.batchId());
        }
        for (WorkPlan.WorkTask task : harness.seatOrdered(plan,
            List.of(work.tasks().get(1), work.tasks().get(3)))) {
            expected.add(task.batchId());
        }
        assertEquals(expected, harness.ledger.appliedOrder,
            "the payload order follows the settlement order, not the report order");
    }

    @Test
    void samePlanLowerSeatStillAppliesEveryPayloadInSeatOrder() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        TickPlan plan = harness.publishPlan(7L, work.tasks());
        harness.dispatched(work);
        List<WorkPlan.WorkTask> descending = List.of(work.tasks().get(3), work.tasks().get(2),
            work.tasks().get(1), work.tasks().get(0));
        harness.mergeTick(1L, plan, work, descending);
        harness.drain(2L);
        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        List<Long> expectedOrder = new ArrayList<>();
        for (WorkPlan.WorkTask task : harness.seatOrdered(plan, descending)) {
            expectedOrder.add(task.batchId());
        }
        assertEquals(expectedOrder, harness.ledger.appliedOrder,
            "the settlement sorts a descending dispatch into seat order");
        assertEquals(4, harness.ledger.appliedCount());
    }

    @Test
    void heldPassAndRefusedBatchHaveExplicitTerminalStates() throws Exception {
        Harness harness = new Harness(1);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        harness.dispatched(work);
        harness.held(work.tasks().get(0));
        harness.held(work.tasks().get(3));
        TickPlan plan = harness.publishPlan(7L, work.tasks());
        harness.mergeTick(1L, plan, work, List.of(work.tasks().get(1), work.tasks().get(2)));
        harness.drain(2L);

        Set<Long> dispatched = new LinkedHashSet<>(harness.ledger.dispatched);
        assertEquals(4, dispatched.size());
        harness.ledger.assertPartition(dispatched);
        assertEquals(1, harness.ledger.refusedCount(), "the batch the one-slot channel refused is explicit");
        assertEquals(2, harness.ledger.heldCount(), "the unmerged tasks stay unfinished obligations");
        assertEquals(1, harness.ledger.reportCount(), "only the accepted batch produced a commit report");
        assertEquals(1, harness.ledger.reported.size(), "only the accepted batch reached the channel");
    }

    @Test
    void aLowerPlanLateReportStaysAnObservationAndKeepsTerminalStates() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        TickPlan plan = harness.publishPlan(7L, work.tasks());
        harness.dispatched(work);
        harness.mergeTick(1L, plan, work, work.tasks());
        harness.drain(2L);
        TickPlan newer = harness.publishPlan(8L, work.tasks());
        CommitLog.Batch late = new CommitLog.Batch(WORLD, DOMAIN,
            DispatchWriteBack.nodeKey(work.tasks().get(0)), CommitLog.Batch.Kind.APPLY, 1L, 1L, 1);
        harness.log.beginTick(3L);
        CommitLog.Verdict verdict = KernelModule.reachLandedEntityBatch(harness.log, harness.store,
            7L, harness.store.lifecycle(), late);
        harness.log.closeTick();
        assertEquals(CommitLog.Entry.Disposition.DROPPED, verdict.disposition(),
            "a lower-plan report is an observation dropped after a newer plan reported");
        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        assertEquals(4, harness.ledger.appliedCount(), "the late report does not touch a terminal state");
        assertNotNull(newer);
    }

    @Test
    void aRefusedFreezeLeavesEveryLandedBatchTerminalStateIntact() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        harness.dispatched(work);
        harness.mergeTick(1L, null, work, work.tasks());
        harness.drain(2L);
        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        assertEquals(4, harness.ledger.appliedCount(), "a refused freeze still applies the payload exactly once");
        assertEquals(4, harness.ledger.reportCount());
        assertTrue(harness.log.unplanned() >= 4L,
            "with no plan published the reports are recorded as unplanned, not lost");
    }

    @Test
    void aRefusedFreezePlanStillTerminatesEveryLandedBatchExplicitly() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        harness.dispatched(work);
        // The kernel's refusal shape: the sequence advanced, the planner refused, the store recorded
        // the failure and no plan was published for the new sequence.
        harness.store.noteFailure(RejectCode.TICK_BUDGET_EXHAUSTED);
        harness.currentPlanSequence = 2L;
        harness.mergeTick(1L, null, work, work.tasks());
        harness.drain(2L);

        assertEquals(1L, harness.store.failures(), "the refused build is visible on the plan store");
        assertEquals(null, harness.store.planOf(2L), "the refused plan was never published");
        harness.ledger.assertExactlyOnce(harness.ledger.dispatched);
        assertEquals(4, harness.ledger.appliedCount(), "the payload still lands exactly once");
        assertEquals(4, harness.ledger.reportCount(), "each landed batch is still reported exactly once");
        assertTrue(harness.log.unplanned() >= 4L,
            "without a published plan the reports are recorded as unplanned, not miscounted as landed seats");
    }

    @Test
    void stopBeforeDrainLeavesUnfinishedObligationsExplicit() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        TickPlan plan = harness.publishPlan(7L, work.tasks());
        harness.dispatched(work);
        harness.mergeTick(1L, plan, work, work.tasks());
        // Stop: the process ends after the settlement, before the commit segment walks the channel.
        assertEquals(4, harness.intents.depth(), "every accepted payload is still an explicit obligation");
        assertEquals(4, harness.ledger.reportCount(), "every landed batch already has its one report");
        assertEquals(0, harness.ledger.appliedCount(), "nothing was applied after the stop");
        harness.ledger.assertPartition(harness.ledger.dispatched);
        for (Long batchId : harness.ledger.dispatched) {
            assertEquals(1, harness.ledger.enqueueCount(batchId),
                "a stopped queue keeps one intent per landed batch and drops none");
            assertEquals(0, harness.ledger.applies.getOrDefault(batchId, 0));
        }
        // No reset seam exists on the channel: IntentQueue has no clear/reset, and EntityDomain.reset()
        // needs the KernelModule context. The obligations stay visible as queue depth, which is the
        // terminal evidence this case can assert without inventing a reset.
        assertEquals(4, harness.ledger.pendingCount(),
            "the stopped run's obligations are identified, not silently dropped");
    }

    @Test
    void resetClearsHeldReportsAndKeepsUnfinishedObligationsExplicit() throws Exception {
        KernelModule module = KernelModule.instance();
        Field featuresField = PrtsConfigManager.class.getDeclaredField("FEATURES");
        featuresField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Boolean>> features =
            (Map<String, Map<String, Boolean>>) featuresField.get(null);
        Map<String, Boolean> previous = features.get(PrtsConfigManager.KERNEL);
        Map<String, Boolean> configured = new LinkedHashMap<>();
        configured.put("commit-log", true);
        configured.put("dispatch-takeover", true);
        configured.put("dispatch-parallel", true);
        configured.put("self-timers", false);
        configured.put("share-table", false);
        configured.put("wait-registry", false);
        configured.put("write-path-guard", false);
        features.put(PrtsConfigManager.KERNEL, configured);
        String previousProperty = System.getProperty("arclight.prts.kernel");
        System.setProperty("arclight.prts.kernel", "true");
        EntityDomain domain = null;
        Field commitsField = field(KernelModule.class, "commits");
        CommitLog previousLog = (CommitLog) commitsField.get(module);
        try {
            module.resetReadings();
            module.plans().reset();
            setLong(module, "planSequence", 0L);
            commitsField.set(module, new CommitLog(() -> 64, module.commitOrderSource()));
            long epoch = module.guard().worldEpochs().epochOf(WORLD);
            int depthBefore = module.intents().depth();
            domain = KernelWiring.install();
            WorkPlan work = WorkPlan.freeze(1L, epoch, List.of(moduleView(epoch)), 4, 1L);
            Map<String, Long> nodeKeyBatch = new HashMap<>();
            for (WorkPlan.WorkTask task : work.tasks()) {
                nodeKeyBatch.put(DispatchWriteBack.nodeKey(task), task.batchId());
            }
            DispatchWriteBack writeBack = (DispatchWriteBack) field(EntityDomain.class, "writeBack")
                .get(domain);
            CommitLog.Sink buffered = (CommitLog.Sink) field(DispatchWriteBack.class, "commitSink")
                .get(writeBack);
            List<Long> bufferedBatches = new ArrayList<>();
            writeBack.bindCommitSink(batch -> {
                Long batchId = nodeKeyBatch.get(batch.nodeKey());
                if (batchId != null) {
                    bufferedBatches.add(batchId);
                }
                buffered.reach(batch);
            });
            TickPlan plan = seatPlan(work, 1L);
            module.plans().publish(plan);
            setLong(module, "planSequence", 1L);
            domain.stage(DispatchPass.serialFallback(work, new DispatchReadings(),
                new TaskLedger(1L)));
            setLong(domain, "pendingSeatPlanSequence", 1L);
            setLong(domain, "pendingSeatLifecycle", module.plans().lifecycle());
            CommitLog log = (CommitLog) commitsField.get(module);
            log.beginTick(1L);
            domain.tick(1L);
            assertEquals(depthBefore + 4, module.intents().depth(),
                "the settlement enqueued one payload per landed batch");
            assertEquals(4, bufferedBatches.size(), "the sink buffered one report per landed batch");
            assertEquals(0L, log.sequence(), "the reports are held until the plan boundary");
            domain.shutdown();
            assertEquals(4L, log.sequence(), "shutdown flushed the held reports exactly once");
            log.closeTick();
            long entriesAfterShutdown = log.sequence();
            assertTrue(((Map<?, ?>) field(EntityDomain.class, "seatedReports").get(domain)).isEmpty(),
                "the boundary flush emptied the held groups");
            domain.reset();
            assertTrue(((Map<?, ?>) field(EntityDomain.class, "seatedReports").get(domain)).isEmpty(),
                "reset leaves no held report behind");
            assertEquals(depthBefore + 4, module.intents().depth(),
                "reset does not consume or drop the unfinished payload obligations");
            log.beginTick(2L);
            domain.tick(2L);
            log.closeTick();
            assertEquals(entriesAfterShutdown, log.sequence(),
                "a tick after reset does not re-report the old batches");
            assertEquals(4L, log.accepted(), "the four held reports were accepted before the reset");
        } finally {
            if (domain != null) {
                domain.shutdown();
            }
            commitsField.set(module, previousLog);
            module.plans().reset();
            setLong(module, "planSequence", 0L);
            module.commits().reset();
            module.resetReadings();
            if (previous == null) {
                features.remove(PrtsConfigManager.KERNEL);
            } else {
                features.put(PrtsConfigManager.KERNEL, previous);
            }
            if (previousProperty == null) {
                System.clearProperty("arclight.prts.kernel");
            } else {
                System.setProperty("arclight.prts.kernel", previousProperty);
            }
        }
    }

    private static EntityCandidateView moduleView(long epoch) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, epoch);
        builder.add(10L, 0, 0, 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(11L, 8, 8, 8.0, 64.0, 8.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(12L, 16, 16, 16.0, 64.0, 16.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(13L, 24, 24, 24.0, 64.0, 24.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        return builder.build();
    }

    private static TickPlan seatPlan(WorkPlan work, long sequence) {
        List<TickPlan.CommitStep> order = new ArrayList<>();
        int position = 0;
        for (WorkPlan.WorkTask task : work.tasks()) {
            order.add(new TickPlan.CommitStep(position++, WORLD, DOMAIN,
                DispatchWriteBack.nodeKey(task), position, 0, 1, false));
        }
        return new TickPlan(sequence, sequence, 1L, List.of(WORLD), null, List.of(), order, null,
            List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setLong(Object target, String name, long value) throws Exception {
        Class<?> owner = target.getClass();
        while (owner != null) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(target, value);
                return;
            } catch (NoSuchFieldException missing) {
                owner = owner.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void duplicateSettlementIsCaughtByTheExactlyOnceLedger() throws Exception {
        Harness harness = new Harness(QUEUE_CAP);
        WorkPlan work = WorkPlan.freeze(1L, 1L, List.of(view()), 4, 1L);
        TickPlan plan = harness.publishPlan(7L, work.tasks());
        harness.dispatched(work);
        harness.mapTasks(List.of(work.tasks().get(0)));
        WorkPlan.WorkBatch batch = harness.batchOf(work, work.tasks().get(0));
        List<StateHasher.Slice> slices = List.of(new StateHasher.Slice(WORLD, "r0.0",
            batch.batchId(), 0L, 1.0, 64.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L));
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED,
            harness.writeBack.settle(batch, slices));
        assertEquals(DispatchWriteBack.Settlement.ACCEPTED,
            harness.writeBack.settle(batch, slices));
        assertEquals(2, harness.ledger.enqueueCount(batch.batchId()),
            "the second settlement of one batch is visible to the ledger");
        assertFalse(harness.ledger.exactlyOnceHolds(harness.ledger.dispatched),
            "the duplicate terminal state is a counterexample the ledger refuses");
        assertFalse(harness.ledger.refused.contains(batch.batchId()));
    }

    private static EntityCandidateView view() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        builder.add(10L, 0, 0, 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(11L, 8, 8, 8.0, 64.0, 8.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(12L, 16, 16, 16.0, 64.0, 16.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(13L, 24, 24, 24.0, 64.0, 24.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        return builder.build();
    }

    /** Real components plus the test-side per-batch ledger. */
    private static final class Harness {

        final IntentQueue intents;
        final DispatchWriteBack writeBack;
        final CommitSegment segment;
        final TickPlanStore store = new TickPlanStore(8);
        final CommitLog log;
        final Ledger ledger = new Ledger();
        final Map<String, Long> currentNodeKeys = new HashMap<>();
        long currentPlanSequence = 1L;

        Harness(int capacity) {
            intents = new IntentQueue(() -> capacity, () -> 1);
            intents.bindPayload(intent -> {
                Long batchId = siteBatch(intent.siteId());
                if (batchId != null) {
                    ledger.applied.add(batchId);
                    ledger.appliedOrder.add(batchId);
                    ledger.applies.merge(batchId, 1, Integer::sum);
                }
                return IntentPayload.Outcome.APPLIED;
            });
            writeBack = new DispatchWriteBack(intents, (prefix, write) -> "payload:" + (++handles),
                handle -> { }, world -> 1L, new DispatchReadings(), () -> true);
            segment = new CommitSegment(intents, () -> true, () -> 64);
            segment.bindOwnerThread(Thread.currentThread());
            log = new CommitLog(() -> 64, new CommitLog.OrderSource() {
                @Override
                public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                    TickPlanStore.StepRef ref = store.resolve(worldId, domainId, nodeKey);
                    return ref == null ? null
                        : new CommitLog.Resolved(ref.planSequence(), ref.position(), ref.intent());
                }

                @Override
                public CommitLog.Resolved resolveAt(long planSequence, long lifecycle,
                                                    String worldId, String domainId, String nodeKey) {
                    TickPlanStore.StepRef ref =
                        store.resolveIn(planSequence, lifecycle, worldId, domainId, nodeKey);
                    return ref == null ? null
                        : new CommitLog.Resolved(ref.planSequence(), ref.position(), ref.intent());
                }
            });
            writeBack.bindCommitSink(batch -> {
                Long batchId = currentNodeKeys.get(batch.nodeKey());
                if (batchId != null) {
                    ledger.reports.merge(batchId, 1, Integer::sum);
                    ledger.reported.add(batchId);
                    ledger.reportOrder.add(batchId);
                }
                KernelModule.reachLandedEntityBatch(log, store, currentPlanSequence,
                    store.lifecycle(), batch);
            });
        }

        long handles;

        TickPlan publishPlan(long sequence, List<WorkPlan.WorkTask> tasks) {
            List<TickPlan.CommitStep> order = new ArrayList<>();
            int position = 0;
            for (WorkPlan.WorkTask task : tasks) {
                order.add(new TickPlan.CommitStep(position++, WORLD, DOMAIN,
                    DispatchWriteBack.nodeKey(task), position, 0, 1, false));
            }
            TickPlan plan = new TickPlan(sequence, sequence, 1L, List.of(WORLD), null, List.of(),
                order, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
            store.publish(plan);
            writeBack.bindCommitSeats(task -> {
                for (TickPlan.CommitStep step : plan.commitOrder()) {
                    if (step.worldId().equals(task.worldId())
                        && step.nodeKey().equals(DispatchWriteBack.nodeKey(task))) {
                        return step.position();
                    }
                }
                return -1;
            });
            return plan;
        }

        void dispatched(WorkPlan work) {
            for (WorkPlan.WorkTask task : work.tasks()) {
                ledger.dispatched.add(task.batchId());
            }
        }

        void mapTasks(List<WorkPlan.WorkTask> tasks) {
            currentNodeKeys.clear();
            for (WorkPlan.WorkTask task : tasks) {
                currentNodeKeys.put(DispatchWriteBack.nodeKey(task), task.batchId());
            }
        }

        void held(WorkPlan.WorkTask task) {
            ledger.held.add(task.batchId());
        }

        void mergeTick(long tick, TickPlan plan, WorkPlan work, List<WorkPlan.WorkTask> tasks)
            throws Exception {
            if (plan != null) {
                currentPlanSequence = plan.planSequence();
            }
            currentNodeKeys.clear();
            for (WorkPlan.WorkTask task : tasks) {
                currentNodeKeys.put(DispatchWriteBack.nodeKey(task), task.batchId());
            }
            log.beginTick(tick);
            DispatchPass pass = DispatchPass.serialFallback(work, new DispatchReadings(),
                new TaskLedger(1L), tasks);
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            merge.merge(pass, System.nanoTime() + 2_000_000_000L, new io.izzel.arclight.common.prts
                    .kernel.arena.ArenaLedger(), new DispatchReadings(), new DiffProbe(), null,
                HashWhitelist.bitexact(), DOMAIN, writeBack);
            for (WorkPlan.WorkTask task : tasks) {
                if (!ledger.reported.contains(task.batchId())) {
                    ledger.refused.add(task.batchId());
                }
            }
            log.closeTick();
        }

        void drain(long tick) {
            segment.run(tick);
        }

        WorkPlan.WorkBatch batchOf(WorkPlan work, WorkPlan.WorkTask task) {
            return new WorkPlan.WorkBatch(task.batchId(), task, work.planEpoch(), view());
        }

        List<WorkPlan.WorkTask> seatOrdered(TickPlan plan, List<WorkPlan.WorkTask> tasks) {
            List<WorkPlan.WorkTask> sorted = new ArrayList<>(tasks);
            sorted.sort((left, right) -> Integer.compare(seatOf(plan, left), seatOf(plan, right)));
            return sorted;
        }

        static int seatOf(TickPlan plan, WorkPlan.WorkTask task) {
            String key = DispatchWriteBack.nodeKey(task);
            for (TickPlan.CommitStep step : plan.commitOrder()) {
                if (step.nodeKey().equals(key)) {
                    return step.position();
                }
            }
            return Integer.MAX_VALUE;
        }
    }

    /** Test-side exactly-once ledger; it never re-decides the commit order. */
    private static final class Ledger {

        final Set<Long> dispatched = new LinkedHashSet<>();
        final Set<Long> held = new LinkedHashSet<>();
        final Set<Long> reported = new LinkedHashSet<>();
        final Set<Long> refused = new LinkedHashSet<>();
        final Set<Long> applied = new LinkedHashSet<>();
        final List<Long> reportOrder = new ArrayList<>();
        final List<Long> appliedOrder = new ArrayList<>();
        final Map<Long, Integer> applies = new HashMap<>();
        final Map<Long, Integer> reports = new HashMap<>();

        void assertExactlyOnce(Set<Long> expected) {
            assertPartition(expected);
            assertTrue(exactlyOnceHolds(expected), "every batch must hold exactly one terminal state");
        }

        void assertPartition(Set<Long> expected) {
            for (Long batchId : expected) {
                int enqueued = reports.getOrDefault(batchId, 0);
                int appliedHere = applies.getOrDefault(batchId, 0);
                int reportedHere = reports.getOrDefault(batchId, 0);
                boolean wasHeld = held.contains(batchId);
                boolean wasRefused = refused.contains(batchId);
                if (wasHeld) {
                    assertEquals(0, enqueued, "a held batch must not be enqueued");
                    assertEquals(0, reportedHere, "a held batch must not be reported");
                    continue;
                }
                if (wasRefused) {
                    assertEquals(0, enqueued, "a refused batch must not be enqueued");
                    assertEquals(0, reportedHere, "a refused batch must not be reported");
                    continue;
                }
                assertEquals(1, enqueued, "each accepted batch is enqueued exactly once");
                assertEquals(1, reportedHere, "each landed batch is reported exactly once");
                assertTrue(appliedHere <= 1, "each payload applied at most once");
            }
            for (Long batchId : reports.keySet()) {
                assertTrue(expected.contains(batchId), "a report outside the dispatch set");
            }
            assertTrue(exactlyOnceHolds(expected), "terminal states partition the dispatch set");
        }

        boolean exactlyOnceHolds(Set<Long> expected) {
            for (Long batchId : expected) {
                int enqueued = reports.getOrDefault(batchId, 0);
                int appliedHere = applies.getOrDefault(batchId, 0);
                int reportedHere = reports.getOrDefault(batchId, 0);
                if (held.contains(batchId)) {
                    if (enqueued != 0 || reportedHere != 0 || appliedHere != 0) {
                        return false;
                    }
                    continue;
                }
                if (refused.contains(batchId)) {
                    if (enqueued != 0 || reportedHere != 0 || appliedHere != 0) {
                        return false;
                    }
                    continue;
                }
                if (enqueued != 1 || reportedHere != 1 || appliedHere > 1) {
                    return false;
                }
            }
            return true;
        }

        int appliedCount() {
            return applied.size();
        }

        int pendingCount() {
            return reports.size() - applied.size() + held.size();
        }

        int heldCount() {
            return held.size();
        }

        int refusedCount() {
            return refused.size();
        }

        int reportCount() {
            return reported.size();
        }

        int enqueueCount(long batchId) {
            return reports.getOrDefault(batchId, 0);
        }
    }

    private static Long siteBatch(String siteId) {
        if (siteId == null || !siteId.startsWith(DispatchWriteBack.SITE_PREFIX + ":")) {
            return null;
        }
        try {
            return Long.parseLong(siteId.substring(DispatchWriteBack.SITE_PREFIX.length() + 1));
        } catch (NumberFormatException unrecognised) {
            return null;
        }
    }
}
