/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.KernelWriteRanges;
import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.PipelineWriteClaims;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WriteClaimTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The batch identity of a real sorter dispatch: the window, the write and the log entry of the
 * task that ran, the three release interleavings, exceptions and the cross-tick chain. */
class PipelineBatchRunsTest {

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String MAILBOX = "main";
    private static final String NODE_KEY = PipelineRoundJobs.keyOf(WORLD, MAILBOX);
    private static final String HOLDER = "domain:" + DOMAIN;
    private static final String SITE = "host:server-thread";
    private static final Object LEVEL = new Object();
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL1 = ChunkPos.asLong(0, 0);
    private static final long CELL2 = ChunkPos.asLong(1, 0);

    @AfterEach
    void clearTaps() {
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
    }

    @Test
    void aBatchBodyOpensItsWindowAndItsWriteReachesTheLogOfItsPlan() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        PipelineBatchLedger.BatchId batch = run.declarePlan(PLAN, TICK);
        assertNotNull(batch);
        assertEquals(1, run.ledger.tasksOf(batch).size());

        run.handOff();
        run.runBody();

        assertEquals(1, run.bodyRuns);
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());
        assertSame(task.task(), run.runningInBody.get());
        assertSame(batch, task.task().batch());
        assertEquals(PLAN, batch.planSequence());
        assertEquals(0, run.claims.openWindows());
        assertEquals(0L, run.ledger.anomalyCount());
        assertEquals(1L, run.commitLog.accepted());
        CommitLog.Replay replay = run.commitLog.closeTick();
        assertEquals(1, replay.loggedSteps());
        assertEquals(PLAN, replay.steps().get(0).planSequence());
        assertTrue(replay.orderMatches());
    }

    @Test
    void theRangeOfOneBatchDoesNotCoverTheWriteOfAnother() {
        Run run = new Run();
        Pending first = run.submit(CELL1, CELL1);
        run.enqueue(first);
        assertNotNull(run.declarePlan(PLAN, TICK));
        run.handOff();
        run.runBody();
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());

        Pending second = run.submit(CELL2, CELL1, CELL2);
        run.enqueue(second);
        assertNotNull(run.declarePlan(PLAN + 1, TICK + 1));
        run.handOff();
        run.runBody();

        assertEquals(List.of(Verdict.CAPTURED, Verdict.CAPTURED, Verdict.CAPTURED),
            run.verdicts(),
            "the retained first range covers both writes from its declared plan");
    }

    @Test
    void aTaskOfAnOlderBatchRunsWithoutTheNewWindow() {
        Run run = new Run();
        Pending late = run.submit(CELL2, CELL2);
        run.enqueue(late);
        PipelineBatchLedger.BatchId old = run.declarePlan(PLAN, TICK);
        assertNotNull(old);
        assertNull(run.declarePlan(PLAN + 1, TICK + 1));

        run.handOff();
        run.runBody();

        assertEquals(List.of(Verdict.CAPTURED), run.verdicts(),
            "the retained claim covers the task after the newer plan has no step");
        assertEquals(1L, run.ledger.lateRunCount());
        assertSame(old, late.task().batch());
        assertInstanceOf(PipelineBatchLedger.Done.class, run.ledger.stateOf(late.task()));
    }

    @Test
    void aTaskSubmittedAfterTheFreezeCarriesNoBatch() {
        Run run = new Run();
        assertNull(run.declarePlan(PLAN, TICK));
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        run.handOff();
        run.runBody();

        assertEquals(List.of(Verdict.DEFAULT_PATH), run.verdicts());
        assertNull(task.task().batch());
        assertEquals(1L, run.ledger.earlyRunCount());
    }

    @Test
    void aTaskWhoseBatchThePlanReplacedDoesNotWriteUnderTheReplacement() {
        Run run = new Run();
        Pending first = run.submit(CELL1, CELL1);
        run.enqueue(first);
        assertNotNull(run.declarePlan(PLAN, TICK));
        Pending second = run.submit(CELL1, CELL1);
        run.enqueue(second);
        PipelineBatchLedger.BatchId replacement = run.declarePlan(PLAN + 1, TICK + 1);
        assertNotNull(replacement);

        run.handOff();
        run.runBody();
        assertEquals(List.of(Verdict.REJECTED), run.verdicts(),
            "the retained old range fails closed rather than opening the new task's window");
        assertEquals(0, run.capturedCount());

        run.runBody();
        assertEquals(List.of(Verdict.REJECTED, Verdict.REJECTED), run.verdicts(),
            "the retained and replacing ranges cover the same cell and stay ambiguous");
        assertSame(replacement, second.task().batch());
    }

    @Test
    void aTaskThatRanBeforeTheFreezeIsNotCut() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        run.handOff();
        run.runBody();
        assertEquals(List.of(Verdict.DEFAULT_PATH), run.verdicts());

        assertNull(run.declarePlan(PLAN, TICK));
        assertEquals(1L, run.ledger.earlyRunCount());
        assertInstanceOf(PipelineBatchLedger.DoneUnclaimed.class, run.ledger.stateOf(task.task()));
    }

    @Test
    void aReleaseClearingTheResidentQueueCancelsTheTask() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        run.release(CELL1);

        assertInstanceOf(PipelineBatchLedger.CancelledUnclaimed.class,
            run.ledger.stateOf(task.task()));
        assertEquals(1L, run.ledger.queuedClearedCount());
        assertEquals(1, run.releaseCallbacks);
        assertEquals(0, run.bodyRuns, "a task the queue cleared never runs");
        assertNull(run.declarePlan(PLAN, TICK));
    }

    @Test
    void aReleaseQueuedBeforeTheSubmitLeavesTheTaskToRun() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.releaseBeforeEnqueue(CELL1);

        assertEquals(1L, run.ledger.releaseBeforeEnqueueCount());
        assertEquals(0L, run.ledger.queuedClearedCount());
        assertInstanceOf(PipelineBatchLedger.Minted.class, run.ledger.stateOf(task.task()));

        run.drainSorterOnce();
        run.ledger.enqueued(task.task());
        assertNotNull(run.declarePlan(PLAN, TICK));
        run.handOff();
        run.runBody();

        assertEquals(1, run.bodyRuns, "the task the release could not reach still runs");
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());
        assertEquals(1, run.releaseCallbacks);
    }

    @Test
    void aReleaseAfterThePopButBeforeTheBodyDoesNotCancelTheTask() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        run.handOff();
        assertInstanceOf(PipelineBatchLedger.HandedOff.class, run.ledger.stateOf(task.task()));

        run.release(CELL1);

        assertInstanceOf(PipelineBatchLedger.HandedOff.class, run.ledger.stateOf(task.task()),
            "a body already handed to its mailbox is no longer in the sorter queue");
        assertEquals(1L, run.ledger.releaseAfterHandoffCount());
        assertEquals(0L, run.ledger.queuedClearedCount());
        run.runBody();
        assertEquals(1, run.bodyRuns);
        assertEquals(List.of(Verdict.DEFAULT_PATH), run.verdicts(),
            "the task was never claimed, so no window opens for it");
    }

    @Test
    void anExceptionOfOneBodyIsNotSwallowedAndTheNextBodyStillWrites() {
        Run run = new Run();
        Pending throwing = run.submit(CELL1, new RuntimeException("body"));
        run.enqueue(throwing);
        Pending writing = run.submit(CELL1, CELL1);
        run.enqueue(writing);
        assertNotNull(run.declarePlan(PLAN, TICK));

        run.handOff();
        RuntimeException thrown = assertThrows(RuntimeException.class, run::drainMainOnce);
        assertEquals("body", thrown.getMessage());
        assertEquals(0, run.claims.openWindows(), "the exception path closes its own window");
        assertInstanceOf(PipelineBatchLedger.Done.class, run.ledger.stateOf(throwing.task()));

        run.drainMainOnce();
        assertEquals(1, run.bodyRuns);
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());
        assertInstanceOf(PipelineBatchLedger.Done.class, run.ledger.stateOf(writing.task()));
    }

    @Test
    void theLandedBatchIsReportedByItsOwnAuthorizingGraph() {
        Run run = new Run();
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        PipelineBatchLedger.BatchId batch = run.declarePlan(PLAN, TICK);
        run.handOff();
        run.runBody();
        assertNotNull(batch);

        List<PipelineWriteClaims.Report> reported = new ArrayList<>();
        assertEquals(1, settle(run.claims, run.graphOf(PLAN), reported::add));
        assertEquals(1L, run.claims.reportedBatchCount());
        assertEquals(NODE_KEY + "#1", reported.get(0).nodeKey());
        assertEquals(1, reported.get(0).rows());
        assertEquals(0L, run.claims.landedWithoutNodeCount());
        assertSame(batch, run.ledger.tasksOf(batch).get(0).batch());
        assertEquals(PLAN, batch.planSequence());
        assertEquals(1L, run.commitLog.accepted(),
            "the write of the batch is accepted by the real commit log");
        CommitLog.Replay replay = run.commitLog.closeTick();
        assertEquals(1, replay.loggedSteps());
        assertEquals(PLAN, replay.steps().get(0).planSequence(),
            "the accepted line belongs to the plan that ordered the batch");
        assertTrue(replay.orderMatches());

        assertNull(run.declarePlan(PLAN + 1, TICK + 1));
        List<PipelineBatchLedger.BatchReport> reports = run.ledger.reports();
        assertEquals(1, reports.size());
        assertSame(batch, reports.get(0).batch());
        assertEquals(1, reports.get(0).done());
    }

    @Test
    void theLandedBatchIsAcceptedByTheRealCommitLogUnderItsOwnPlan() {
        Run run = new Run();
        run.deferCommit = true;
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        assertNotNull(run.declarePlan(PLAN, TICK));
        run.handOff();
        run.runBody();
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());
        assertEquals(0L, run.commitLog.accepted(), "the write alone reaches no commit log");

        JobGraph next = run.publishPlan(PLAN + 1, TICK + 1);
        List<PipelineWriteClaims.Report> reported = new ArrayList<>();
        assertEquals(1, settle(run.claims, next, reported::add));
        assertEquals(1L, run.claims.reportedBatchCount());
        assertEquals(NODE_KEY + "#1", reported.get(0).nodeKey());
        assertEquals(1, reported.get(0).rows());
        assertEquals(0L, run.claims.landedWithoutNodeCount(),
            "the landing is not degraded to a landing without a node");
        for (PipelineWriteClaims.Report report : reported) {
            run.reach(report);
        }
        assertEquals(1L, run.commitLog.accepted(),
            "the reported batch is accepted by the real commit log");
        CommitLog.Replay replay = run.commitLog.closeTick();
        assertEquals(1, replay.loggedSteps());
        assertEquals(PLAN, replay.steps().get(0).planSequence(),
            "the accepted line is attributed to the plan that ordered the batch");
        assertTrue(replay.orderMatches());

        run.declareToClaims(next, TICK + 1);
        assertEquals(0, settle(run.claims, next, reported::add), "a batch is reported once");
        assertEquals(0L, run.claims.landedWithoutNodeCount());
    }

    @Test
    void aLandedBatchIsAttributedToItsPlanWhenTheNextPlanReusesItsKey() {
        Run run = new Run();
        run.deferCommit = true;
        Pending task = run.submit(CELL1, CELL1);
        run.enqueue(task);
        assertNotNull(run.declarePlan(PLAN, TICK));
        run.handOff();
        run.runBody();
        assertEquals(List.of(Verdict.CAPTURED), run.verdicts());
        assertEquals(0L, run.commitLog.accepted(), "the write alone reaches no commit log");

        JobGraph next = run.publishReusingPlan(PLAN + 1, TICK + 1);
        assertEquals(2, next.nodes().size());
        assertEquals(PLAN + 1, run.plans.resolve(WORLD, DOMAIN, NODE_KEY).planSequence(),
            "the newest plan still answers the node key for its old callers");
        assertEquals(1, positionIn(next, NODE_KEY),
            "the next plan moved the same key to another seat");

        List<PipelineWriteClaims.Report> reported = new ArrayList<>();
        assertEquals(1, settle(run.claims, next, reported::add));
        PipelineWriteClaims.Report report = reported.get(0);
        assertEquals(PLAN, report.planSequence());
        assertEquals(0, report.position(), "the report carries the seat of the plan that declared it");
        assertEquals(0L, report.lifecycle());

        CommitLog latest = run.anotherLog();
        latest.beginTick(TICK);
        assertTrue(latest.reach(run.batchOf(report)).logged());
        CommitLog.Replay misattributed = latest.closeTick();
        assertEquals(PLAN, misattributed.steps().get(0).planSequence(),
            "the batch key keeps the report on the plan that declared it");
        assertEquals(0, misattributed.steps().get(0).position());

        run.reach(report);

        assertEquals(1L, run.commitLog.accepted());
        CommitLog.Replay replay = run.commitLog.closeTick();
        assertEquals(1, replay.loggedSteps());
        assertEquals(PLAN, replay.steps().get(0).planSequence(),
            "the accepted line is attributed to the plan that declared the span");
        assertEquals(0, replay.steps().get(0).position(),
            "the line carries the original seat, not the seat the next plan moved the key to");
        assertTrue(replay.orderMatches());
    }

    private static int positionIn(JobGraph graph, String nodeKey) {
        for (JobGraph.Node node : graph.nodes()) {
            if (node.key().equals(nodeKey)) {
                return node.position();
            }
        }
        return -1;
    }

    /** Reports the closed span through the shape the tree carries: the one-argument settlement when
     * the claim point keeps the authorizing plan, and the graph-bearing shape otherwise. The
     * evidence run against the previous blob takes the second path with the plan that just froze,
     * which is the defect this batch closes. */
    private static int settle(PipelineWriteClaims claims, JobGraph latest, Consumer<PipelineWriteClaims.Report> sink) {
        int buffered;
        try {
            try {
                buffered = (int) PipelineWriteClaims.class.getMethod("reportLanded", Consumer.class)
                    .invoke(claims, sink);
            } catch (NoSuchMethodException oldShape) {
                buffered = (int) PipelineWriteClaims.class.getMethod("reportLanded", JobGraph.class,
                    Consumer.class).invoke(claims, latest, sink);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the claim point carries neither settlement shape", failure);
        }
        claims.flushAllReports(sink);
        return buffered;
    }

    /** The real sorter, the real claim point and the real commit log of one test; only the level a
     * world is read as, the executor stepping and the step of the plan are fixtures. */
    private static final class Run {

        private final WritePathCounters counters = new WritePathCounters();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final WriteLedger writeLedger = new WriteLedger();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, writeLedger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, writeLedger);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final Source source = new Source();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);
        private final PipelineWriteClaims claims = new PipelineWriteClaims(versions,
            guard.worldEpochs(), () -> true, world -> LEVEL);
        private final PipelineBatchLedger ledger = new PipelineBatchLedger(64, 100);
        private final ManualExecutor sorterExecutor = new ManualExecutor();
        private final ManualExecutor mainExecutor = new ManualExecutor();
        private final ProcessorMailbox<Runnable> main;
        private final ChunkTaskPriorityQueueSorter sorter;
        private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> queue;
        private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Release> releases;
        private final CommitLog commitLog;
        private final TickPlanStore plans = new TickPlanStore(2);
        private final Map<Long, JobGraph> graphs = new HashMap<>();
        private final AtomicReference<PipelineBatchLedger.TrackedTask> runningInBody =
            new AtomicReference<>();
        private final List<Verdict> decisions = new ArrayList<>();
        private boolean deferCommit;
        private int bodyRuns;
        private int captured;
        private int releaseCallbacks;

        private Run() {
            mainExecutor.defer();
            main = ProcessorMailbox.create(mainExecutor, MAILBOX);
            List<ProcessorHandle<?>> processors = List.of(main);
            sorter = new ChunkTaskPriorityQueueSorter(processors, sorterExecutor, 8);
            queue = sorter.getProcessor(main, true);
            releases = sorter.getReleaseProcessor(main);
            sorterExecutor.defer();
            PrtsPipelineRows.bindMailboxWorlds(Map.of(main, WORLD));
            commitLog = new CommitLog(() -> 64, orderSource());
            commitLog.beginTick(TICK);
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.claimLandedTap((slot, world, tick, row) -> claims.noteLanded(slot, world, row));
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD));
            versions.refresh(true);
            slots.refresh(true);
            grants.refresh(true);
            claims.table(slots);
            claims.owners(owners);
            claims.ledger(ledger);
            claims.writeSpaces(KernelWriteRanges.reader());
            claims.refresh(true);
            PrtsPipelineRows.installOwnerTap(claims);
        }

        private Pending submit(long cell, long... writes) {
            return submit(cell, null, writes);
        }

        private Pending submit(long cell, RuntimeException failure, long... writes) {
            PipelineBatchLedger.TrackedTask task = ledger.mint(main, WORLD, MAILBOX, cell, TICK);
            Pending pending = new Pending(task, cell, writes, failure);
            Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> {
                runBodyOf(pending);
                reply.tell(Unit.INSTANCE);
            };
            Function<ProcessorHandle<Unit>, Runnable> wrapped =
                PipelineBatchWrapper.function(ledger, task, delegate);
            queue.tell(ChunkTaskPriorityQueueSorter.message(wrapped, cell, level()));
            return pending;
        }

        private static IntSupplier level() {
            return () -> 1;
        }

        private void enqueue(Pending pending) {
            drainSorterOnce();
            ledger.enqueued(pending.task());
        }

        private void handOff() {
            drainSorterOnce();
        }

        private void runBody() {
            drainMainOnce();
        }

        private void runBodyOf(Pending pending) {
            runningInBody.set(ledger.runningTask());
            if (pending.failure() != null) {
                throw pending.failure();
            }
            bodyRuns++;
            for (long write : pending.writes()) {
                WriteControlledSlots.Decision decision = slots.judge(LEVEL,
                    WritePath.BLOCK_WRITE.key(), positionIn(write), TICK);
                decisions.add(decision.verdict());
                if (decision.verdict() == Verdict.CAPTURED) {
                    captured++;
                    WriteControlledSlots.Slot slot = slots.lastCoveredSlot();
                    claims.noteLanded(slot, WORLD);
                    if (!deferCommit) {
                        reached(slot.planNodeKey(), slot.expectedVersion(),
                            WriteControlledSlots.foldValue(slot.writeSet()), 1);
                    }
                }
            }
        }

        private void reach(PipelineWriteClaims.Report report) {
            commitLog.reach(new CommitLog.Batch(WORLD, DOMAIN, report.nodeKey(),
                CommitLog.Batch.Kind.APPLY, report.expectedVersion(), report.writeSetDigest(),
                report.rows()), report.planSequence(), report.position(), report.lifecycle());
        }

        private void reached(String nodeKey, long expectedVersion, long writeSetDigest, int rows) {
            commitLog.reach(new CommitLog.Batch(WORLD, DOMAIN, nodeKey,
                CommitLog.Batch.Kind.APPLY, expectedVersion, writeSetDigest, rows));
        }

        private CommitLog.Batch batchOf(PipelineWriteClaims.Report report) {
            return new CommitLog.Batch(WORLD, DOMAIN, report.nodeKey(), CommitLog.Batch.Kind.APPLY,
                report.expectedVersion(), report.writeSetDigest(), report.rows());
        }

        /** A second log over the same plan store, for the old lookup shape. */
        private CommitLog anotherLog() {
            return new CommitLog(() -> 64, orderSource());
        }

        private CommitLog.OrderSource orderSource() {
            return new CommitLog.OrderSource() {

                @Override
                public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                    TickPlanStore.StepRef step = plans.resolve(worldId, domainId, nodeKey);
                    return step == null ? null
                        : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
                }

                @Override
                public CommitLog.Resolved resolveAt(long planSequence, long lifecycle,
                                                    String worldId, String domainId, String nodeKey) {
                    TickPlanStore.StepRef step =
                        plans.resolveIn(planSequence, lifecycle, worldId, domainId, nodeKey);
                    return step == null ? null
                        : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
                }
            };
        }

        private void release(long cell) {
            releases.tell(ChunkTaskPriorityQueueSorter.release(() -> {
                releaseCallbacks++;
                ledger.released(main, cell, true);
            }, cell, true));
            drainSorterOnce();
        }

        private void releaseBeforeEnqueue(long cell) {
            release(cell);
        }

        private void drainSorterOnce() {
            sorterExecutor.drainOne();
        }

        private void drainMainOnce() {
            mainExecutor.drainOne();
        }

        private PipelineBatchLedger.BatchId declarePlan(long planSequence, long tick) {
            PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, planSequence, tick);
            JobGraph graph = graphOf(batch, planSequence, tick);
            publish(graph, tick);
            declareToClaims(graph, tick);
            return batch;
        }

        /** Publishes the plan that follows, with a node of its own, without declaring it to the
         * claim point: the order the production freeze uses, where the closed span is settled
         * before the new declarations. */
        private JobGraph publishPlan(long planSequence, long tick) {
            return publishSinglePlan(planSequence, tick, NODE_KEY + "#next");
        }

        /** Publishes one plan whose commit order carries KEY at another position than the plan
         * before it, without declaring it to the claim point. */
        private JobGraph publishReusingPlan(long planSequence, long tick) {
            JobDeclaration filler = declaration("chunk-pipeline/" + WORLD + "/filler", 1L);
            JobDeclaration same = declaration(NODE_KEY, 1L);
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(List.of(filler, same), tick,
                planSequence, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code());
            publish(frozen.graph(), tick);
            return frozen.graph();
        }

        private JobGraph publishSinglePlan(long planSequence, long tick, String nodeKey) {
            JobDeclaration declaration = declaration(nodeKey, 1L);
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(List.of(declaration), tick,
                planSequence, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code());
            publish(frozen.graph(), tick);
            return frozen.graph();
        }

        private static JobDeclaration declaration(String nodeKey, long handle) {
            JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(WORLD,
                WriteLevel.REGION, DOMAIN, HolderKind.REGISTERED, HOLDER, 40);
            return new JobDeclaration(nodeKey, handle, WORLD, DOMAIN, 0, List.of(), 0, MAILBOX,
                "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
                JobDeclaration.SiteClass.PARALLEL, 0, 1, List.of(demand),
                new JobDeclaration.WriteRange(JobDeclaration.RangeGranularity.CHUNK,
                    new long[] {CELL1}));
        }

        private void declareToClaims(JobGraph graph, long tick) {
            claims.declare(graph, graph.planSequence(), tick, plans.lifecycle());
        }

        private JobGraph graphOf(PipelineBatchLedger.BatchId batch, long planSequence, long tick) {
            List<JobDeclaration> declarations = new ArrayList<>();
            if (batch != null) {
                JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
                JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(WORLD,
                    WriteLevel.REGION, DOMAIN, HolderKind.REGISTERED, HOLDER, 40);
                declarations.add(new JobDeclaration(PipelineRoundJobs.batchKeyOf(batch), 1L,
                    WORLD, DOMAIN, 0, List.of(), 0,
                    MAILBOX, "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
                    JobDeclaration.SiteClass.PARALLEL, 0, ledger.tasksOf(batch).size(),
                    List.of(demand), new JobDeclaration.WriteRange(
                        JobDeclaration.RangeGranularity.CHUNK, ledger.cellsOf(batch))));
            }
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(declarations, tick,
                planSequence, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code());
            return frozen.graph();
        }

        private void publish(JobGraph graph, long tick) {
            graphs.put(graph.planSequence(), graph);
            plans.publish(planOf(graph));
            Map<OwnershipDomain, Long> granted = new HashMap<>();
            for (JobGraph.Node node : graph.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    OwnershipDomain domain =
                        new OwnershipDomain(ref.worldId(), WriteLevel.REGION, ref.domainId());
                    granted.put(domain,
                        versions.grant(ref.worldId(), WriteLevel.REGION, ref.domainId()));
                }
            }
            versions.noteGranted(graph.planSequence(), granted);
            grants.apply(graph, tick);
            source.steps.clear();
            for (JobGraph.Node node : graph.nodes()) {
                source.steps.put(node.key(), new WriteControlledSlots.Source.Step(
                    graph.planSequence(), node.position()));
            }
        }

        /** The real plan store entry of a frozen graph: the commit order is the nodes' order, which
         * is the order the freeze gave them. */
        private static TickPlan planOf(JobGraph graph) {
            List<TickPlan.CommitStep> order = new ArrayList<>();
            int position = 0;
            for (JobGraph.Node node : graph.nodes()) {
                order.add(new TickPlan.CommitStep(position++, node.worldId(), node.domainId(),
                    node.key(), node.nodeId(), node.batchKind(), node.batchBound(), false));
            }
            return new TickPlan(graph.tickIndex(), graph.planSequence(), graph.worldSetGeneration(),
                List.of(WORLD), graph, graph.order(), order, null, List.of(), 0,
                graph.splitIntents(), 0L, TickPlanStore.Feedback.none());
        }

        private JobGraph graphOf(long planSequence) {
            return graphs.get(planSequence);
        }

        private List<Verdict> verdicts() {
            return List.copyOf(decisions);
        }

        private int capturedCount() {
            return captured;
        }

        private static WritePosition positionIn(long cell) {
            return WritePosition.at(ChunkPos.getX(cell) * 16 + 8, 64,
                ChunkPos.getZ(cell) * 16 + 8);
        }
    }

    private record Pending(PipelineBatchLedger.TrackedTask task, long cell, long[] writes,
                           RuntimeException failure) {
    }

    /** The plan step a write is read against; a plan that declared nothing answers none. */
    private static final class Source implements WriteControlledSlots.Source {

        private final Map<String, Step> steps = new HashMap<>();

        @Override
        public String worldOf(Object levelRef) {
            return levelRef == LEVEL ? WORLD : "";
        }

        @Override
        public Step stepOf(String worldId, String domainId, String nodeKey) {
            return steps.get(nodeKey);
        }
    }

    /** One executor the test steps by hand: a mailbox run queued here stays queued until drained. */
    private static final class ManualExecutor implements Executor {

        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean inline = true;

        @Override
        public void execute(Runnable command) {
            if (inline) {
                command.run();
            } else {
                queue.add(command);
            }
        }

        private void defer() {
            inline = false;
        }

        private void drainOne() {
            Runnable next = queue.poll();
            if (next == null) {
                throw new AssertionError("the executor holds no task to drain");
            }
            next.run();
        }
    }
}
