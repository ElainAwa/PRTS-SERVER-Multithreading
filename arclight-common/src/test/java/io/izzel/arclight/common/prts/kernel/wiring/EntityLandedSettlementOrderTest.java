/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A landed batch is settled and reported in the order the plan that seats it published, even when
 * the dispatch order came from the plan of the previous tick and runs the other way. The check
 * itself is unchanged: the merge walks the plan's places, so the report sequence can never descend
 * against the plan while every payload still reaches the channel exactly once. Without the seat
 * order the same pass reports the later place first and the log drops the earlier one. */
class EntityLandedSettlementOrderTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = EntityDomain.ID;
    private static final long TICK = 1L;
    private static final long SEQUENCE = 7L;

    @Test
    void theDispatchOrderAloneWouldViolateTheSeatingOrder() {
        Run run = run(false);

        assertEquals(2, run.bound().get(), "both landed batches carried their payload into the channel");
        assertEquals(2L, run.intents().enqueuedCount(), "a refused report still carries its payload");
        assertEquals(1L, run.log().accepted());
        assertEquals(1L, run.log().orderViolations(), "the dispatch order descends against the plan");
        assertEquals(1L, run.log().dropped());
    }

    @Test
    void aTaskThePlanDoesNotSeatHasNoPlace() {
        WorkPlan work = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan(work.tasks().get(1), 0, work.tasks().get(0), 1));
        WorkPlan.WorkTask absent = new WorkPlan.WorkTask(9L, WORLD, "r9.9", 9L, 0, 1, 1L, 1L, 0,
            "r9.9");

        assertEquals(1, EntityDomain.landedSeat(store.planOf(SEQUENCE), work.tasks().get(0)));
        assertEquals(-1, EntityDomain.landedSeat(store.planOf(SEQUENCE), absent),
            "a task the plan does not seat claims no place and settles after the planned ones");
        assertEquals(-1, EntityDomain.landedSeat(null, work.tasks().get(0)));
    }

    @Test
    void aLandedBatchSettlesInTheSeatingPlansOrderNotTheDispatchOrder() {
        Run run = run(true);
        WorkPlan.WorkTask firstDispatched = run.work().tasks().get(0);
        WorkPlan.WorkTask secondDispatched = run.work().tasks().get(1);

        assertEquals(2, run.frame().committed(), "both frozen tasks were committed");
        assertEquals(2, run.bound().get(), "both landed batches carried their payload into the channel");
        assertEquals(2L, run.intents().enqueuedCount(), "a reordered settlement still enqueues every payload");
        assertEquals(2L, run.log().accepted(), "both reports landed under the plan's own places");
        assertEquals(0L, run.log().orderViolations(), "the settlement descended against the seating plan");
        assertEquals(0L, run.log().dropped());
        assertEquals(0L, run.log().unplanned());
        CommitLog.Replay replay = run.log().closeTick();
        assertEquals(2, replay.loggedSteps(), "the log keeps one line per landed batch");
        assertEquals(0, replay.steps().get(0).position(), "the seat-0 batch is reported first");
        assertEquals(1, replay.steps().get(1).position());
        assertTrue(replay.orderMatches());

        CommitSegment segment = new CommitSegment(run.intents(), () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());
        segment.run(TICK + 1L);
        assertEquals(2, run.applied().size(), "every payload was consumed");
        assertEquals(DispatchWriteBack.SITE_PREFIX + ":" + secondDispatched.batchId(),
            run.applied().get(0).siteId(), "the channel consumed the seat-0 payload first");
        assertEquals(DispatchWriteBack.SITE_PREFIX + ":" + firstDispatched.batchId(),
            run.applied().get(1).siteId());
    }

    /** One merge of the two frozen regions with the seating plan reversed against the dispatch
     * order; the seat lookup is bound only when the fixed path is the one under test. */
    private static Run run(boolean bindSeats) {
        WorkPlan work = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        assertEquals(2, work.taskCount(), "the two regions are the two frozen tasks");
        WorkPlan.WorkTask firstDispatched = work.tasks().get(0);
        WorkPlan.WorkTask secondDispatched = work.tasks().get(1);
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan(secondDispatched, 0, firstDispatched, 1));

        CommitLog log = log(store);
        log.beginTick(TICK);
        IntentQueue intents = new IntentQueue(() -> 8, () -> 1);
        List<WriteIntent> applied = new ArrayList<>();
        intents.bindPayload(intent -> {
            applied.add(intent);
            return IntentPayload.Outcome.APPLIED;
        });
        Map<String, PrtsWorldWriteTaps.DeferredWrite> payloads = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        AtomicInteger bound = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                payloads.put(handle, write);
                bound.incrementAndGet();
                return handle;
            }, payloads::remove, world -> 1L, new DispatchReadings(), () -> true);
        writeBack.bindCommitSink(batch -> {
            TickPlanStore.StepRef seat = store.resolveIn(SEQUENCE, store.lifecycle(),
                batch.worldId(), batch.domainId(), batch.nodeKey());
            log.reach(batch, seat.planSequence(), seat.position(), store.lifecycle());
        });
        if (bindSeats) {
            writeBack.bindCommitSeats(task -> EntityDomain.landedSeat(store.planOf(SEQUENCE), task));
        }

        DispatchReadings readings = new DispatchReadings();
        TaskLedger ledger = new TaskLedger(1L);
        DispatchPass pass = DispatchPass.serialFallback(work, readings, ledger);
        MergeSegment merge = new MergeSegment();
        merge.bindOwnerThread(Thread.currentThread());
        MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L,
            new ArenaLedger(), readings, new DiffProbe(), null, HashWhitelist.bitexact(), DOMAIN,
            writeBack);
        return new Run(work, frame, log, intents, applied, bound);
    }

    private record Run(WorkPlan work, MergeSegment.Frame frame, CommitLog log, IntentQueue intents,
                       List<WriteIntent> applied, AtomicInteger bound) {
    }

    private static EntityCandidateView view() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        builder.add(10L, 0, 0, 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(11L, 8, 0, 8.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        return builder.build();
    }

    /** The same plan's seats arriving in two merges: the first merge reports the even seats and the
     * second the odd ones, so each merge is ascending on its own and the log still sees the second
     * merge start before the first merge's last seat. This pins the mechanism a tick-wide report
     * order has to remove; it is not a claim about how often the production path produces it. */
    @Test
    void theSamePlansSeatsSplitAcrossTwoMergesLoseTheOrder() {
        WorkPlan firstDispatched = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        WorkPlan secondDispatched = WorkPlan.freeze(TICK + 1L, 2L, List.of(farView()), 4, 10L);
        TickPlanStore store = new TickPlanStore(8);
        List<TickPlan.CommitStep> order = new ArrayList<>();
        order.add(step(firstDispatched.tasks().get(0), 0, 1L));
        order.add(step(secondDispatched.tasks().get(0), 1, 2L));
        order.add(step(firstDispatched.tasks().get(1), 2, 3L));
        order.add(step(secondDispatched.tasks().get(1), 3, 4L));
        store.publish(new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none()));
        CommitLog log = log(store);
        log.beginTick(TICK);

        mergeOnce(firstDispatched, store, log);
        mergeOnce(secondDispatched, store, log);

        assertEquals(4L, log.sequence(), "each landed batch keeps its own report");
        assertEquals(3L, log.accepted());
        assertEquals(1L, log.dropped(), "the second merge opens before the first merge's last seat");
        assertEquals(1L, log.orderViolations(),
            "a plan whose seats arrive in two merges is reported out of its own order");
        assertEquals(0L, log.unplanned());
    }

    @Test
    void theTickWideOrderRestoresAPlansSplitSeats() {
        WorkPlan firstDispatched = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        WorkPlan secondDispatched = WorkPlan.freeze(TICK + 1L, 2L, List.of(farView()), 4, 10L);
        List<TickPlan.CommitStep> order = new ArrayList<>();
        order.add(step(firstDispatched.tasks().get(0), 0, 1L));
        order.add(step(secondDispatched.tasks().get(0), 1, 2L));
        order.add(step(firstDispatched.tasks().get(1), 2, 3L));
        order.add(step(secondDispatched.tasks().get(1), 3, 4L));
        TickPlan plan = new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        List<CommitLog.Batch> reports = new ArrayList<>();
        reports.add(batchOf(firstDispatched.tasks().get(0)));
        reports.add(batchOf(firstDispatched.tasks().get(1)));
        reports.add(batchOf(secondDispatched.tasks().get(0)));
        reports.add(batchOf(secondDispatched.tasks().get(1)));

        EntityDomain.orderLandedBatches(reports, plan);

        assertEquals(List.of(DispatchWriteBack.nodeKey(firstDispatched.tasks().get(0)),
            DispatchWriteBack.nodeKey(secondDispatched.tasks().get(0)),
            DispatchWriteBack.nodeKey(firstDispatched.tasks().get(1)),
            DispatchWriteBack.nodeKey(secondDispatched.tasks().get(1))),
            reports.stream().map(CommitLog.Batch::nodeKey).toList(),
            "the tick-wide flush restores the plan's own seat order");
    }

    private static CommitLog.Batch batchOf(WorkPlan.WorkTask task) {
        return new CommitLog.Batch(task.worldId(), DOMAIN, DispatchWriteBack.nodeKey(task),
            CommitLog.Batch.Kind.APPLY, task.worldEpoch(), 1L, 1);
    }

    @Test
    void aPlansReportsSpreadOverTwoTicksAreReportedTogetherInSeatOrder() {
        WorkPlan firstDispatched = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        WorkPlan secondDispatched = WorkPlan.freeze(TICK + 1L, 2L, List.of(farView()), 4, 10L);
        List<TickPlan.CommitStep> order = new ArrayList<>();
        order.add(step(firstDispatched.tasks().get(0), 0, 1L));
        order.add(step(secondDispatched.tasks().get(0), 1, 2L));
        order.add(step(firstDispatched.tasks().get(1), 2, 3L));
        order.add(step(secondDispatched.tasks().get(1), 3, 4L));
        TickPlan plan = new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        TickPlanStore store = new TickPlanStore(8);
        store.publish(plan);
        List<CommitLog.Batch> firstTick = List.of(batchOf(firstDispatched.tasks().get(0)),
            batchOf(firstDispatched.tasks().get(1)));
        List<CommitLog.Batch> secondTick = List.of(batchOf(secondDispatched.tasks().get(0)),
            batchOf(secondDispatched.tasks().get(1)));

        CommitLog perTick = log(store);
        perTick.beginTick(TICK);
        for (CommitLog.Batch batch : firstTick) {
            reachSeat(perTick, store, batch);
        }
        perTick.closeTick();
        perTick.beginTick(TICK + 1L);
        for (CommitLog.Batch batch : secondTick) {
            reachSeat(perTick, store, batch);
        }
        perTick.closeTick();
        assertEquals(1L, perTick.orderViolations(),
            "reports of one plan spread over two ticks descend when each tick is sorted alone");

        CommitLog aggregated = log(store);
        aggregated.beginTick(TICK + 1L);
        List<CommitLog.Batch> wholePlan = new ArrayList<>(firstTick);
        wholePlan.addAll(secondTick);
        EntityDomain.orderLandedBatches(wholePlan, plan);
        for (CommitLog.Batch batch : wholePlan) {
            reachSeat(aggregated, store, batch);
        }
        aggregated.closeTick();
        assertEquals(4L, aggregated.accepted());
        assertEquals(0L, aggregated.dropped());
        assertEquals(0L, aggregated.orderViolations(),
            "the whole plan reported together stays in its own seat order");
    }

    @Test
    void aLateOldPlanReportIsNotInsertedAfterANewerPlansReport() {
        WorkPlan previous = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        WorkPlan next = WorkPlan.freeze(TICK + 1L, 2L, List.of(farView()), 4, 10L);
        List<TickPlan.CommitStep> previousOrder = new ArrayList<>();
        previousOrder.add(step(previous.tasks().get(0), 0, 1L));
        TickPlan previousPlan = new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null,
            List.of(), previousOrder, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        List<TickPlan.CommitStep> nextOrder = new ArrayList<>();
        nextOrder.add(step(next.tasks().get(0), 0, 2L));
        nextOrder.add(step(next.tasks().get(1), 1, 3L));
        TickPlan nextPlan = new TickPlan(SEQUENCE + 1L, SEQUENCE + 1L, 1L, List.of(WORLD), null,
            List.of(), nextOrder, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        TickPlanStore store = new TickPlanStore(8);
        store.publish(previousPlan);
        store.publish(nextPlan);
        CommitLog.Batch stale = batchOf(previous.tasks().get(0));
        CommitLog.Batch first = batchOf(next.tasks().get(0));
        CommitLog.Batch second = batchOf(next.tasks().get(1));

        CommitLog inserted = log(store);
        inserted.beginTick(TICK);
        reachSeat(inserted, store, SEQUENCE + 1L, first);
        reachSeat(inserted, store, SEQUENCE, stale);
        inserted.closeTick();
        assertEquals(1L, inserted.orderViolations(),
            "a late old-plan report inserted after the new plan is judged descending");
        assertEquals(1L, inserted.dropped());

        CommitLog clean = log(store);
        clean.beginTick(TICK);
        reachSeat(clean, store, SEQUENCE + 1L, first);
        Map<Long, List<CommitLog.Batch>> held = new LinkedHashMap<>();
        Map<Long, Long> lifecycles = new LinkedHashMap<>();
        held.put(SEQUENCE, new ArrayList<>(List.of(stale)));
        lifecycles.put(SEQUENCE, store.lifecycle());
        List<CommitLog.Batch> observed =
            EntityDomain.takeStaleLandedReports(held, lifecycles, SEQUENCE + 1L);
        assertEquals(1, observed.size(), "the stale observation line is kept out of the ring");
        assertEquals(stale.nodeKey(), observed.get(0).nodeKey(), "the landed batch is not lost");
        assertTrue(held.isEmpty(), "the stale group does not linger for a later tick");
        reachSeat(clean, store, SEQUENCE + 1L, second);
        CommitLog.Replay replay = clean.closeTick();
        assertEquals(2L, clean.accepted(), "the new plan's lines are still accepted");
        assertEquals(0L, clean.dropped());
        assertEquals(0L, clean.orderViolations());
        assertTrue(replay.orderMatches());
    }

    @Test
    void aPreviousPlansHeldReportsLandBeforeTheNextPlansReport() {
        WorkPlan previous = WorkPlan.freeze(TICK, 1L, List.of(view()), 4, 1L);
        WorkPlan next = WorkPlan.freeze(TICK + 1L, 2L, List.of(farView()), 4, 10L);
        List<TickPlan.CommitStep> previousOrder = new ArrayList<>();
        previousOrder.add(step(previous.tasks().get(0), 0, 1L));
        previousOrder.add(step(previous.tasks().get(1), 2, 2L));
        TickPlan previousPlan = new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null,
            List.of(), previousOrder, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        List<TickPlan.CommitStep> nextOrder = new ArrayList<>();
        nextOrder.add(step(next.tasks().get(0), 0, 3L));
        TickPlan nextPlan = new TickPlan(SEQUENCE + 1L, SEQUENCE + 1L, 1L, List.of(WORLD), null,
            List.of(), nextOrder, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        TickPlanStore store = new TickPlanStore(8);
        store.publish(previousPlan);
        store.publish(nextPlan);
        List<CommitLog.Batch> held = new ArrayList<>();
        held.add(batchOf(previous.tasks().get(1)));
        held.add(batchOf(previous.tasks().get(0)));

        CommitLog ordered = log(store);
        ordered.beginTick(TICK);
        EntityDomain.orderLandedBatches(held, previousPlan);
        for (CommitLog.Batch batch : held) {
            reachSeat(ordered, store, SEQUENCE, batch);
        }
        reachSeat(ordered, store, SEQUENCE + 1L, batchOf(next.tasks().get(0)));
        CommitLog.Replay replay = ordered.closeTick();
        assertEquals(3L, ordered.accepted());
        assertEquals(0L, ordered.dropped());
        assertEquals(0L, ordered.orderViolations(),
            "the held old plan is reported in its own seat order before the new plan's line");
        assertTrue(replay.orderMatches());

        CommitLog inverted = log(store);
        inverted.beginTick(TICK);
        reachSeat(inverted, store, SEQUENCE + 1L, batchOf(next.tasks().get(0)));
        for (CommitLog.Batch batch : held) {
            reachSeat(inverted, store, SEQUENCE, batch);
        }
        inverted.closeTick();
        assertEquals(1L, inverted.orderViolations(),
            "reporting the new plan first turns the old plan's first line into a descending one");
        assertEquals(1L, inverted.dropped());
    }

    private static void reachSeat(CommitLog log, TickPlanStore store, CommitLog.Batch batch) {
        reachSeat(log, store, SEQUENCE, batch);
    }

    private static void reachSeat(CommitLog log, TickPlanStore store, long sequence,
                                  CommitLog.Batch batch) {
        TickPlanStore.StepRef seat = store.resolveIn(sequence, store.lifecycle(), batch.worldId(),
            batch.domainId(), batch.nodeKey());
        log.reach(batch, seat.planSequence(), seat.position(), store.lifecycle());
    }

    /** One merge of a pass whose landed batches are reported through a seat-ordered sink. */
    private static void mergeOnce(WorkPlan work, TickPlanStore store, CommitLog log) {
        IntentQueue intents = new IntentQueue(() -> 8, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> payloads = new LinkedHashMap<>();
        AtomicInteger handles = new AtomicInteger();
        DispatchWriteBack writeBack = new DispatchWriteBack(intents,
            (prefix, write) -> {
                String handle = prefix + ":" + handles.incrementAndGet();
                payloads.put(handle, write);
                return handle;
            }, payloads::remove, world -> 1L, new DispatchReadings(), () -> true);
        writeBack.bindCommitSink(batch -> {
            TickPlanStore.StepRef seat = store.resolveIn(SEQUENCE, store.lifecycle(),
                batch.worldId(), batch.domainId(), batch.nodeKey());
            log.reach(batch, seat.planSequence(), seat.position(), store.lifecycle());
        });
        writeBack.bindCommitSeats(task -> EntityDomain.landedSeat(store.planOf(SEQUENCE), task));
        DispatchPass pass = DispatchPass.serialFallback(work, new DispatchReadings(),
            new TaskLedger(1L));
        MergeSegment merge = new MergeSegment();
        merge.bindOwnerThread(Thread.currentThread());
        merge.merge(pass, System.nanoTime() + 2_000_000_000L, new ArenaLedger(),
            new DispatchReadings(), new DiffProbe(), null, HashWhitelist.bitexact(), DOMAIN,
            writeBack);
    }

    /** The same two-region shape at chunks that fall in the region keys of a second plan. */
    private static EntityCandidateView farView() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(WORLD, 1L);
        builder.add(20L, 16, 0, 16.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(21L, 24, 0, 24.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        return builder.build();
    }

    /** One plan that seats the second dispatched task first and the first dispatched task after it. */
    private static TickPlan plan(WorkPlan.WorkTask seatedFirst, int firstPosition,
                                 WorkPlan.WorkTask seatedSecond, int secondPosition) {
        List<TickPlan.CommitStep> order = new ArrayList<>();
        order.add(step(seatedFirst, firstPosition, 1L));
        order.add(step(seatedSecond, secondPosition, 2L));
        return new TickPlan(SEQUENCE, SEQUENCE, 1L, List.of(WORLD), null, List.of(), order, null,
            List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    private static TickPlan.CommitStep step(WorkPlan.WorkTask task, int position, long node) {
        return new TickPlan.CommitStep(position, task.worldId(), DOMAIN,
            DispatchWriteBack.nodeKey(task), node, 0, 1, false);
    }

    private static CommitLog log(TickPlanStore store) {
        return new CommitLog(() -> 16, new CommitLog.OrderSource() {

            @Override
            public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                TickPlanStore.StepRef step = store.resolve(worldId, domainId, nodeKey);
                return step == null ? null
                    : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
            }

            @Override
            public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                                String domainId, String nodeKey) {
                TickPlanStore.StepRef step =
                    store.resolveIn(planSequence, lifecycle, worldId, domainId, nodeKey);
                return step == null ? null
                    : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
            }
        });
    }
}
