/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.PipelineRangeSeals;
import io.izzel.arclight.common.prts.kernel.PipelineWriteClaims;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.observe.ArrivalDigestObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkDemandObserver;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanPlanner;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteClaimTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.SlotKey;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsChunkSorterSubmit;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input closure of one chunk pipeline submit, exercised through the production classes alone:
 * the real sorter submit point mints the task, the real ledger freezes it into a batch, the real
 * round jobs declare it, the real tick planner plans it, the real write claims claim it, and the
 * real commit log records the landing under the plan and seat that authorized it.
 *
 * <p>The positive chain asserts the identity at every hop. The normalisation cases assert what a
 * window can and cannot compare: the same fixture normalises to the same (world, mailbox, count,
 * cells) identity even when the run-local sequence differs, while the same counts with one other
 * cell never normalise to the same identity. The negative cases assert the refusals that keep a
 * missing sink, an unplaced mailbox, an empty freeze, a reset handle, a missing control frame, a
 * plan sequence rollback, an unpublished binding, a landing without a seat and a report without a
 * plan or seat from silently becoming an identity.
 */
class R02InputClosureChainTest {

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String MAILBOX = "main";
    private static final String HOLDER = "domain:" + DOMAIN;
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long EPOCH = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final long CELL2 = ChunkPos.asLong(1, 0);
    private static final long CELL3 = ChunkPos.asLong(2, 0);
    private static final Object LEVEL = new Object();
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);
    private static final WriteSpace SPACE =
        WriteSpace.of(CellKind.SECTION, SectionPos.asLong(0, 4, 0));

    @BeforeEach
    void setUp() {
        clearSeams();
        PrtsPipelineBatches.resetHostTicked();
    }

    @AfterEach
    void tearDown() {
        clearSeams();
    }

    private static void clearSeams() {
        PrtsPipelineRows.installOwnerTap(null);
        PipelineRunSeal.installRange(null);
        PipelineRunSeal.install(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
    }

    // ---------------------------------------------------------------- positive chain

    @Test
    void thewholeIdentityChainTravelsFromSubmitToCommit() {
        ChainFixture fixture = new ChainFixture(CELL);
        SorterRig rig = new SorterRig(fixture.ledger, true);
        PipelineRunSeal.install(task -> new DemandShadowCandidates.TaskStatus("", null));
        PrtsPipelineRows.installOwnerTap(fixture.jobs);
        PrtsPipelineRows.installOwnerTap(fixture.claims);

        AtomicInteger bodies = new AtomicInteger();
        AtomicReference<PipelineBatchLedger.TrackedTask> taskRef = new AtomicReference<>();
        AtomicReference<ChainOutcome> outcome = new AtomicReference<>();
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> {
            bodies.incrementAndGet();
            assertNotNull(PipelineRunSeal.current(), "the body runs inside the started run window");
            outcome.set(fixture.landInWindow(taskRef.get()));
            reply.tell(Unit.INSTANCE);
        };

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.sorter, rig.mailbox, delegate, CELL);

        // H1: the submit seam wraps the function and mints one task, naming this mailbox and cell.
        assertNotSame(delegate, wrapped, "the sorter queues the wrapper the submit point built");
        assertEquals(1L, PrtsPipelineBatches.hostTickedCount());
        assertEquals(1L, fixture.ledger.mintedCount());
        rig.offer(wrapped);
        PrtsPipelineBatches.enqueued(wrapped);

        // H2: the freeze cuts the pending task into a batch naming this world and mailbox.
        assertEquals(1, fixture.jobs.declareInto(fixture.intake, PLAN, TICK));
        List<JobDeclaration> declarations = fixture.intake.take();
        assertEquals(1, declarations.size());
        JobDeclaration declaration = declarations.get(0);
        PipelineBatchLedger.BatchId batch = batchOf(declaration.key(), PLAN);
        assertTrue(fixture.ledger.bindingPublished(batch), "the freeze published the batch binding");
        assertEquals(PipelineRoundJobs.batchKeyOf(batch), declaration.key());
        List<PipelineBatchLedger.TrackedTask> tasks = fixture.ledger.tasksOf(batch);
        assertEquals(1, tasks.size());
        PipelineBatchLedger.TrackedTask task = tasks.get(0);
        taskRef.set(task);
        assertEquals(CELL, task.id().cell());
        assertEquals(WORLD, task.id().worldId());
        assertEquals(MAILBOX, task.id().mailbox());
        assertEquals(TICK, task.id().mintTick());
        assertEquals(batch, task.batch());
        assertEquals(1, task.id().ordinal());

        // H3: the declaration names the same job, batch and write range the freeze produced.
        assertEquals(1, declaration.batchBound());
        assertEquals(1, declaration.writeSet().size());
        assertEquals(1, declaration.ownerDemands().size());
        assertEquals(JobDeclaration.SiteClass.PARALLEL, declaration.siteClass());
        assertNotNull(declaration.range());
        assertEquals(1, declaration.range().cells().length);
        assertEquals(CELL, declaration.range().cells()[0]);
        assertEquals(declaration.key(), PipelineRoundJobs.keyOf(WORLD, MAILBOX) + "#"
            + batch.freezeOrdinal());
        PipelineRoundJobs.Trace trace = fixture.jobs.traceOf(declaration.key());
        assertNotNull(trace, "the declared key has a trace in the same freeze");
        assertEquals(declaration.key(), trace.key());
        assertEquals(declaration.handle(), trace.handle());
        assertEquals(declaration.batchBound(), trace.rounds());

        // H4: the planner freezes the graph and the commit order from the declaration alone; the
        // same input normalises to the same content hash twice.
        TickPlanPlanner.Result planned = TickPlanPlanner.plan(plannerInput(declaration));
        assertTrue(planned.ok(), () -> "refused: " + planned.code() + " " + planned.item());
        TickPlan plan = planned.plan();
        assertEquals(PLAN, plan.planSequence());
        assertEquals(1, plan.graph().nodeCount());
        assertEquals(declaration.key(), plan.graph().nodes().get(0).key());
        assertEquals(1, plan.commitOrder().size());
        assertEquals(declaration.key(), plan.commitOrder().get(0).nodeKey());
        assertEquals(0, plan.commitOrder().get(0).position());
        TickPlanPlanner.Result again = TickPlanPlanner.plan(plannerInput(declaration));
        assertTrue(again.ok());
        assertEquals(plan.contentHash(), again.plan().contentHash(),
            "the same frozen input builds the same plan hash");
        JobDeclaration moved = new JobDeclaration(declaration.key() + "-moved",
            declaration.handle(), WORLD, DOMAIN, 0, List.of(), 0, MAILBOX, "world:" + WORLD,
            declaration.readSet(), declaration.writeSet(), declaration.shareClass(),
            declaration.siteClass(), declaration.batchKind(), declaration.batchBound(),
            declaration.ownerDemands(),
            new JobDeclaration.WriteRange(JobDeclaration.RangeGranularity.CHUNK,
                new long[] {CELL2}));
        TickPlanPlanner.Result changed = TickPlanPlanner.plan(plannerInput(moved));
        assertTrue(changed.ok());
        assertNotEquals(plan.contentHash(), changed.plan().contentHash(),
            "one other declaration builds another plan hash");
        fixture.plans.publish(plan);

        // H5: the claim names the planned node, seat, plan, batch and expected version.
        fixture.publishGrants(plan.graph(), plan.planSequence());
        assertEquals(1, fixture.claims.declare(plan.graph(), plan.planSequence(), TICK,
            fixture.plans.lifecycle()));
        List<PipelineWriteClaims.Claim> claims = fixture.claims.claims();
        assertEquals(1, claims.size());
        PipelineWriteClaims.Claim claim = claims.get(0);
        assertEquals(declaration.key(), claim.nodeKey());
        assertEquals(plan.planSequence(), claim.planSequence());
        assertEquals(plan.graph().nodes().get(0).position(), claim.position());
        assertEquals(batch, claim.batch());
        assertEquals(HOLDER, claim.holderSiteId());
        assertTrue(claim.writeSet().contains(WritePath.BLOCK_WRITE.key()));
        fixture.source.step = new WriteControlledSlots.Source.Step(claim.planSequence(),
            claim.position());

        // H6/H7: the real run window opens around the body, the landing names the seat the plan
        // authorized, and the commit log records that plan and seat.
        rig.drainSorter();
        rig.drainSorter();
        rig.drainMain();

        assertEquals(1, bodies.get());
        assertEquals(PipelineBatchLedger.Done.class, fixture.ledger.stateOf(task).getClass());
        assertEquals(0L, fixture.ledger.anomalyCount());
        assertEquals(0L, fixture.ledger.earlyRunCount());
        assertEquals(0L, fixture.ledger.lateRunCount());
        assertEquals(1L, fixture.claims.landedWriteCount(),
            "the body landed one write through the window it opened");

        ChainOutcome outcomeValue = outcome.get();
        assertNotNull(outcomeValue, "the body produced the landing outcome");
        PipelineWriteClaims.Report report = outcomeValue.report();
        assertEquals(WORLD, report.worldId());
        assertEquals(DOMAIN, report.domainId());
        assertEquals(claim.nodeKey(), report.nodeKey());
        assertEquals(claim.planSequence(), report.planSequence());
        assertEquals(claim.position(), report.position());
        assertEquals(fixture.plans.lifecycle(), report.lifecycle());
        assertEquals(claim.expectedVersion(), report.expectedVersion());
        assertEquals(claim.writeSet(), outcomeValue.slot().writeSet());
        assertEquals(claim.position(), outcomeValue.slot().position());
        assertEquals(claim.nodeKey(), outcomeValue.slot().planNodeKey());

        CommitLog.Verdict logged = outcomeValue.logged();
        assertTrue(logged.logged(), "the report of this plan and seat is logged");
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, logged.disposition());
        assertEquals(1L, fixture.log.accepted());
        assertEquals(List.of(new CommitLog.Entry.Step(report.planSequence(), report.position(),
            report.writeSetDigest())), outcomeValue.replay().steps(),
            "the replay carries the plan and seat the landing named");
        assertTrue(outcomeValue.replay().orderMatches());
        assertTrue(outcomeValue.ringPushed() > 0L, "the landed batch pushed its commit ring");

        // The settlement payload keeps the identity the write was attributed through the window.
        List<PipelineWriteClaims.Attributed> settlements = fixture.settlements;
        assertEquals(1, settlements.size());
        PipelineWriteClaims.Attributed attributed = settlements.get(0);
        assertTrue(attributed.attributed());
        assertTrue(attributed.rowsNamed());
        assertEquals(1, attributed.cells().size());
        assertEquals(CELL, attributed.cells().get(0).cell());
        assertEquals(task.id().ordinal(), attributed.cells().get(0).taskOrdinal());
        assertEquals(1, attributed.cells().get(0).rows());
    }

    // ---------------------------------------------------------------- normalisation

    @Test
    void theSameFixtureNormalisesToTheSameIdentity() {
        NormalisedIdentity first = normalise(11L, CELL, CELL2);
        NormalisedIdentity second = normalise(12L, CELL, CELL2);

        assertEquals(first, second,
            "two independent assemblies of the same fixture normalise to one identity");
        assertEquals(2, first.taskCount());
        assertEquals(List.of(CELL, CELL2), first.cells());
        assertEquals(2L, first.minted());
        assertEquals(2L, first.claimed());
        assertEquals(2L, first.hostTicked());
        assertEquals(1, first.declarations());
    }

    @Test
    void n11TheSameCountsWithADifferentCellDoNotNormaliseToTheSameIdentity() {
        NormalisedIdentity left = normalise(13L, CELL, CELL2);
        NormalisedIdentity right = normalise(14L, CELL, CELL3);

        assertEquals(left.taskCount(), right.taskCount(), "the task counts are equal");
        assertEquals(left.declarations(), right.declarations());
        assertEquals(left.minted(), right.minted());
        assertEquals(left.claimed(), right.claimed());
        assertEquals(left.hostTicked(), right.hostTicked());
        assertNotEquals(left, right,
            "equal counts do not make equal identities: one cell moved");
        assertNotEquals(left.cells(), right.cells());
    }

    @Test
    void n12TwoIndependentRunsWithDifferentArrivalRowsDoNotNormaliseToTheSameIdentity() {
        ArrivalIdentity first = arrivalIdentity(2, 0);
        ArrivalIdentity sameAgain = arrivalIdentity(2, 0);

        assertEquals(first, sameAgain,
            "the same arrival run normalises to the same row identity");

        ArrivalIdentity second = arrivalIdentity(1, 1);
        assertEquals(first.requests(), second.requests(), "the request totals are equal");
        assertEquals(first.satisfied(), second.satisfied());
        assertEquals(first.writes(), second.writes());
        assertEquals(first.rows().size(), second.rows().size());
        assertNotEquals(first, second,
            "equal totals with a different arrival row do not normalise to the same identity");
        assertNotEquals(first.rows().get(0), second.rows().get(0),
            "the first tick carries a different arrival row");
        assertNotEquals(first.rows().get(1), second.rows().get(1),
            "the second tick carries a different arrival row");
    }

    // ---------------------------------------------------------------- negative cases

    @Test
    void n01WithoutASinkTheSubmitKeepsItsFunctionAndMintsNothing() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        SorterRig rig = new SorterRig(ledger, false);
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> { };

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.sorter, rig.mailbox, delegate, CELL);

        assertSame(delegate, wrapped, "no sink means no identity to wrap");
        assertEquals(1L, PrtsPipelineBatches.hostTickedCount(),
            "the host entry is still counted, even when it mints nothing");
        assertFalse(PrtsPipelineBatches.installed());
        assertEquals(0L, ledger.mintedCount());
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        jobs.bind(ledger);
        assertEquals(0, jobs.declareInto(new JobIntake(() -> 16), PLAN, TICK));
    }

    @Test
    void n02AnUnplacedMailboxMintsNothingEvenWithASink() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        SorterRig rig = new SorterRig(ledger, true);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> () -> { };

        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PrtsChunkSorterSubmit.wrap(rig.sorter, rig.mailbox, delegate, CELL);

        assertSame(delegate, wrapped, "a mailbox on no world has no world to name");
        assertEquals(1L, PrtsPipelineBatches.hostTickedCount());
        assertEquals(PrtsPipelineRows.UNPLACED_WORLD,
            PrtsPipelineRows.worldOf(rig.mailbox));
        assertEquals(0L, ledger.mintedCount());
        PipelineRoundJobs jobs = new PipelineRoundJobs();
        jobs.bind(ledger);
        assertEquals(0, jobs.declareInto(new JobIntake(() -> 16), PLAN, TICK));
    }

    @Test
    void n03AnEmptyFreezeDeclaresNoJob() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));
        assertEquals(1L, ledger.emptyFreezeCount());
        assertTrue(ledger.freezeAll(PLAN, TICK).isEmpty());
        assertEquals(0, ledger.pendingCount());

        PipelineRoundJobs jobs = new PipelineRoundJobs();
        jobs.bind(ledger);
        JobIntake intake = new JobIntake(() -> 16);
        assertEquals(0, jobs.declareInto(intake, PLAN, TICK));
        assertTrue(intake.take().isEmpty());
        assertEquals(0L, jobs.declared());
    }

    @Test
    void n04AResetOldTaskIsDetachedFromRunAndTransitions() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        PipelineBatchLedger.TrackedTask task =
            ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        assertTrue(ledger.runStart(task) || task.state() instanceof PipelineBatchLedger.Claimed,
            "the freeze claimed the task before the reset");

        ledger.reset();
        PipelineBatchLedger.TaskState afterReset = task.state();
        assertEquals(1L, ledger.resetCount());
        assertEquals(0, ledger.pendingCount());
        assertEquals(0L, ledger.claimedTaskCount());
        assertFalse(task.live(), "the reset detaches the old handle");
        assertFalse(ledger.runStart(task), "a reset handle cannot open a run window");
        assertNull(ledger.runningTask());

        ledger.enqueued(task);
        assertSame(afterReset, task.state(), "a transition through the old handle moves no state");
        assertEquals(0L, ledger.anomalyCount());
    }

    @Test
    void n05APlanningPeriodWithoutAControlFrameIsRefused() {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(plannerInput(
            TickPlanStore.Control.missing(), List.of()));

        assertFalse(result.ok());
        assertEquals(RejectCode.COUNTER_MISSING, result.code());
        assertEquals("control frame", result.item());
        assertNull(result.plan());
    }

    @Test
    void n06APlanSequenceThatDoesNotAdvanceIsRefused() {
        TickPlanStore.Control control = TickPlanStore.Control.of(TICK, PLAN, 1L, 1.0, 0L, 0L,
            4.0, "normal");
        TickPlanPlanner.Result result = TickPlanPlanner.plan(new TickPlanPlanner.Input(TICK, PLAN,
            1L, List.of(WORLD), List.of(DOMAIN), control, List.of(), shareTable(), 64,
            TickPlanStore.Feedback.none()));

        assertFalse(result.ok());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, result.code());
        assertTrue(result.item().startsWith("plan sequence "), result.item());
        assertNull(result.plan());
    }

    @Test
    void n07AnUnpublishedBindingPublishesNoSeal() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        DemandShadowCandidates candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
        TickPlanStore plans = new TickPlanStore(8);
        RangeSealTable table = new RangeSealTable(ledger.pendingLimit());
        DeclarationInstanceAllocator instances = new DeclarationInstanceAllocator();
        FrozenDeclarations declarations = FrozenDeclarations.of(
            new FrozenDeclarations.FreezeKey("host/1/0", 1L), PLAN, List.of()).declarations();
        PipelineRangeSeals factory = new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> declarations);
        PipelineRunSeal.installRange(factory);

        PipelineBatchLedger.TrackedTask first = ledger.mint(new Object(), WORLD, MAILBOX, CELL,
            TICK);
        ledger.enqueued(first);
        PipelineBatchLedger.TrackedTask second = ledger.mint(new Object(), WORLD, MAILBOX, CELL,
            TICK);
        ledger.enqueued(second);
        PipelineBatchLedger.BatchId forming = new PipelineBatchLedger.BatchId(WORLD, MAILBOX,
            500L, PLAN);
        setState(first, new PipelineBatchLedger.Claimed(forming,
            PipelineBatchLedger.Place.QUEUED));
        setState(second, new PipelineBatchLedger.Claimed(forming,
            PipelineBatchLedger.Place.QUEUED));
        setForming(ledger, forming.freezeOrdinal(), true);
        plans.publish(plan(PLAN, PipelineRoundJobs.batchKeyOf(forming)));
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK));
        setState(first, new PipelineBatchLedger.Running(forming));

        assertFalse(ledger.bindingPublished(forming), "the forming binding is not published");
        assertEquals(2, ledger.tasksOf(forming).size(),
            "only the forming fallback can read its members");
        assertEquals(-1L, ledger.ownerOrdinal(forming, CELL));
        FrozenDeclarations.RangeSeal sealed = factory.seal(first, statusOf(first, forming),
            factory.generation());

        assertNull(sealed, "an unpublished binding publishes no half seal");
        assertEquals(0, table.size());
        assertTrue(factory.refused() >= 1L);
    }

    @Test
    void n08AStampOfAnotherBatchPublishesNoSeal() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        DemandShadowCandidates candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
        TickPlanStore plans = new TickPlanStore(8);
        RangeSealTable table = new RangeSealTable(ledger.pendingLimit());
        DeclarationInstanceAllocator instances = new DeclarationInstanceAllocator();
        FrozenDeclarations declarations = FrozenDeclarations.of(
            new FrozenDeclarations.FreezeKey("host/1/0", 1L), PLAN, List.of()).declarations();
        PipelineRangeSeals factory = new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> declarations);
        PipelineRunSeal.installRange(factory);

        PipelineBatchLedger.TrackedTask first = ledger.mint(new Object(), WORLD, MAILBOX, CELL,
            TICK);
        ledger.enqueued(first);
        PipelineBatchLedger.TrackedTask second = ledger.mint(new Object(), WORLD, MAILBOX, CELL2,
            TICK);
        ledger.enqueued(second);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        plans.publish(plan(PLAN, PipelineRoundJobs.batchKeyOf(batch)));
        setState(first, new PipelineBatchLedger.Running(batch));

        PipelineBatchLedger.TaskId id = first.id();
        DemandShadowCandidates.TaskStatus good = statusOf(first, batch);
        DemandShadowCandidates.TaskStatus otherBatch = new DemandShadowCandidates.TaskStatus(
            good.status(), new DemandShadowCandidates.TaskStamp(id.ordinal(), id.worldId(),
                id.mailbox(), id.cell(), id.mintTick(), batch.freezeOrdinal() + 1L,
                batch.planSequence(), 77L));
        long refusedBefore = factory.refused();

        FrozenDeclarations.RangeSeal mismatched = factory.seal(first, otherBatch,
            factory.generation());

        assertNull(mismatched, "a stamp of another batch publishes no seal");
        assertEquals(0, table.size(), "no half seal of the other batch entered the table");
        assertTrue(factory.refused() > refusedBefore, "the mismatch is refused by the factory");

        FrozenDeclarations.RangeSeal sealed = factory.seal(first, good, factory.generation());
        assertNotNull(sealed, "the same running task publishes the seal of its own batch");
        assertTrue(sealed.wellFormed());
        assertEquals(id.ordinal(), sealed.stamp().taskOrdinal());
        assertEquals(id.cell(), sealed.stamp().cell());
        assertEquals(batch.freezeOrdinal(), sealed.stamp().batchFreezeOrdinal());
        assertEquals(batch.planSequence(), sealed.stamp().planSequence());
        assertArrayEquals(ledger.cellsOf(batch), sealed.cells());
        assertEquals(1, table.size());
    }

    @Test
    void n09ALandingWithoutAPlanNodeIsNotReported() {
        ChainFixture fixture = new ChainFixture(CELL);
        JobDeclaration declaration = ownDeclaration();
        JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(List.of(declaration), TICK, PLAN,
            1L, 16);
        assertTrue(frozen.ok(), () -> "refused: " + frozen.code() + " " + frozen.item());
        JobGraph graph = frozen.graph();
        fixture.publishGrants(graph, PLAN);
        fixture.claims.declare(graph, PLAN, TICK, 0L);

        SlotKey key = new SlotKey(WORLD, DOMAIN, WriteLevel.REGION, SlotKey.segmentOf(SPACE),
            PLAN);
        WriteControlledSlots.Slot unseated = new WriteControlledSlots.Slot(key, LEVEL,
            DOMAIN + "/" + WORLD + "/" + MAILBOX + "#999", 99, 1L, 1L, EPOCH, TICK + 40,
            HolderKind.REGISTERED, HOLDER, Set.of(WritePath.BLOCK_WRITE.key()), SPACE);
        fixture.claims.noteLanded(unseated, WORLD);
        assertEquals(1L, fixture.claims.landedWriteCount());

        List<PipelineWriteClaims.Report> reports = new ArrayList<>();
        assertEquals(0, fixture.claims.reportLanded(reports::add),
            "a landing whose node the plan does not carry is never reported");
        assertTrue(reports.isEmpty());
        assertEquals(1L, fixture.claims.landedWithoutNodeCount());
    }

    @Test
    void n10ACommitReportWithoutAPlanOrSeatIsRefused() {
        TickPlanStore store = new TickPlanStore(4);
        store.publish(plan(PLAN, 0));
        CommitLog log = commitLog(store);
        log.beginTick(1L);

        assertRefused(log, reach(log, report(99L, 0, store.lifecycle())));
        assertRefused(log, reach(log, report(PLAN, 1, store.lifecycle())));
        assertEquals(0L, log.accepted());
        CommitLog.Replay replay = log.closeTick();
        for (CommitLog.Entry.Step step : replay.steps()) {
            assertEquals(0L, step.planSequence(),
                "a refused report is never attributed to a real plan");
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** The production objects of one chain run; only the world, the plan and the level are fixtures. */
    private static final class ChainFixture {

        private final long cell;
        private final PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final WorldEpochs epochs = new WorldEpochs();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots;
        private final PipelineWriteClaims claims;
        private final JobIntake intake = new JobIntake(() -> 16);
        private final PipelineRoundJobs jobs = new PipelineRoundJobs();
        private final TickPlanStore plans = new TickPlanStore(8);
        private final CommitLog log;
        private final List<PipelineWriteClaims.Attributed> settlements = new ArrayList<>();

        private ChainFixture(long cell) {
            this.cell = cell;
            this.epochs.observe(List.of(WORLD));
            this.slots = new WriteControlledSlots(owners, versions, epochs, source);
            this.claims = new PipelineWriteClaims(versions, epochs, () -> true,
                world -> LEVEL, settlements::add);
            versions.refresh(true);
            slots.refresh(true);
            grants.refresh(true);
            claims.table(slots);
            claims.owners(owners);
            claims.ledger(ledger);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
            jobs.bind(ledger);
            log = new CommitLog(() -> 1, commitOrder(plans));
        }

        /** Grants the versions and owner tokens the graph declares, the way a planning period does. */
        private void publishGrants(JobGraph graph, long planSequence) {
            Map<OwnershipDomain, Long> granted = new HashMap<>();
            for (JobGraph.Node node : graph.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    long version = versions.grant(ref.worldId(), WriteLevel.REGION,
                        ref.domainId());
                    granted.put(new OwnershipDomain(ref.worldId(), WriteLevel.REGION,
                        ref.domainId()), version);
                }
            }
            versions.noteGranted(planSequence, granted);
            grants.apply(graph, TICK);
        }

        /** Lands one write inside the run window, reports it and commits the report. */
        private ChainOutcome landInWindow(PipelineBatchLedger.TrackedTask task) {
            WriteControlledSlots.Verdict judgement = slots.judge(LEVEL,
                WritePath.BLOCK_WRITE.key(), WITNESS, TICK).verdict();
            assertEquals(WriteControlledSlots.Verdict.CAPTURED, judgement,
                () -> "refusal=" + slots.lastRefusal());
            WriteControlledSlots.Slot slot = slots.lastCoveredSlot();
            assertNotNull(slot);
            assertEquals(PLAN, slot.key().planSequence());

            claims.noteLanded(slot, WORLD, new WorldWriteGuard.LandedRow(WORLD, DOMAIN,
                slot.planNodeKey(), slot.key().planSequence(), slot.position(),
                slot.expectedVersion(), slot.worldEpoch(), TICK, 8, 64, 8));
            List<PipelineWriteClaims.Report> reports = new ArrayList<>();
            assertEquals(1, claims.reportLanded(reports::add));
            assertEquals(0L, claims.landedWithoutNodeCount());
            PipelineWriteClaims.Report report = reports.get(0);

            log.beginTick(TICK);
            CommitLog.Verdict verdict = log.reach(new CommitLog.Batch(report.worldId(),
                report.domainId(), report.nodeKey(), CommitLog.Batch.Kind.APPLY,
                report.expectedVersion(), report.writeSetDigest(), report.rows()),
                report.planSequence(), report.position(), report.lifecycle());
            CommitLog.Replay replay = log.closeTick();
            long pushed = 0L;
            for (CommitRing ring : log.rings()) {
                pushed += ring.pushed();
            }
            return new ChainOutcome(slot, report, verdict, replay, pushed,
                List.copyOf(settlements));
        }
    }

    private record ChainOutcome(WriteControlledSlots.Slot slot, PipelineWriteClaims.Report report,
                                CommitLog.Verdict logged, CommitLog.Replay replay,
                                long ringPushed,
                                List<PipelineWriteClaims.Attributed> settlements) {
    }

    /** What a window can compare: the fixture's world, mailbox, task count and sorted cells. */
    private record NormalisedIdentity(String world, String mailbox, int taskCount,
                                      List<Long> cells, int declarations, long minted,
                                      long claimed, long hostTicked) {
    }

    /** The observer's windowed constructor is package-private to the observe package; the fixture
     * reaches the same production type through the one constructor it declares, with no test hook in
     * the production path. */
    private static ArrivalDigestObserver arrivalObserver(int window) {
        try {
            java.lang.reflect.Constructor<ArrivalDigestObserver> constructor =
                ArrivalDigestObserver.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(window);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** One arrival row normalised to a run's first tick: the row identity, not only its totals. */
    private record ArrivalRow(long relativeTick, String world, long requests, long satisfied,
                              long missed, long blocking, long futuresTaken, long futuresCompleted,
                              long inFlight, long writes) {
    }

    /** What an arrival window folds to: every row in order plus the totals the rows carry. */
    private record ArrivalIdentity(List<ArrivalRow> rows, long requests, long satisfied,
                                   long writes) {
    }

    /** Runs one arrival window over the production observer: per-tick demand, one write, one fold. */
    private static ArrivalIdentity arrivalIdentity(int... requestsPerTick) {
        ArrivalDigestObserver observer = arrivalObserver(4);
        ChunkDemandObserver demand = new ChunkDemandObserver();
        observer.demand(demand);
        demand.attach();
        observer.attach();
        try {
            for (int index = 0; index < requestsPerTick.length; index++) {
                long tick = index + 1L;
                for (int count = 0; count < requestsPerTick[index]; count++) {
                    demand.demandStarted(WORLD, "full", false);
                }
                if (index == 0) {
                    observer.writeAtWorld(WORLD);
                }
                observer.arrivalTick(tick, List.of(WORLD));
            }
            long requests = 0L;
            long satisfied = 0L;
            long writes = 0L;
            long firstTick = observer.firstTick();
            List<ArrivalRow> rows = new ArrayList<>();
            for (ArrivalDigestObserver.Row row : observer.rows()) {
                rows.add(new ArrivalRow(row.tick() - firstTick, row.world(), row.requests(),
                    row.satisfied(), row.missed(), row.blocking(), row.futuresTaken(),
                    row.futuresCompleted(), row.inFlight(), row.writes()));
                requests += row.requests();
                satisfied += row.satisfied();
                writes += row.writes();
            }
            return new ArrivalIdentity(List.copyOf(rows), requests, satisfied, writes);
        } finally {
            observer.detach();
            demand.detach();
        }
    }

    private static NormalisedIdentity normalise(long planSequence, long... cells) {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        SorterRig rig = new SorterRig(ledger, true);
        PrtsPipelineBatches.resetHostTicked();
        try {
            for (long cell : cells) {
                Function<ProcessorHandle<Unit>, Runnable> wrapped = PrtsChunkSorterSubmit.wrap(
                    rig.sorter, rig.mailbox, reply -> () -> { }, cell);
                rig.offer(wrapped);
                PrtsPipelineBatches.enqueued(wrapped);
            }
            JobIntake intake = new JobIntake(() -> 16);
            PipelineRoundJobs jobs = new PipelineRoundJobs();
            jobs.bind(ledger);
            assertTrue(jobs.declareInto(intake, planSequence, TICK) > 0);
            List<JobDeclaration> declarations = intake.take();
            Set<Long> allCells = new LinkedHashSet<>();
            int taskCount = 0;
            for (JobDeclaration declaration : declarations) {
                PipelineBatchLedger.BatchId batch = batchOf(declaration.key(), planSequence);
                taskCount += declaration.batchBound();
                for (long cell : ledger.cellsOf(batch)) {
                    allCells.add(cell);
                }
            }
            List<Long> sorted = new ArrayList<>(allCells);
            sorted.sort(Long::compareTo);
            return new NormalisedIdentity(WORLD, MAILBOX, taskCount, List.copyOf(sorted),
                declarations.size(), ledger.mintedCount(), ledger.claimedTaskCount(),
                PrtsPipelineBatches.hostTickedCount());
        } finally {
            PrtsPipelineBatches.uninstall();
            PrtsPipelineRows.bindMailboxWorlds(Map.of());
        }
    }

    private static PipelineBatchLedger.BatchId batchOf(String declarationKey, long planSequence) {
        int hash = declarationKey.lastIndexOf('#');
        assertTrue(hash > 0 && hash < declarationKey.length() - 1, declarationKey);
        String[] parts = declarationKey.substring(0, hash).split("/", -1);
        assertEquals(3, parts.length, declarationKey);
        long freezeOrdinal = Long.parseLong(declarationKey.substring(hash + 1));
        PipelineBatchLedger.BatchId batch = new PipelineBatchLedger.BatchId(parts[1], parts[2],
            freezeOrdinal, planSequence);
        assertEquals(declarationKey, PipelineRoundJobs.batchKeyOf(batch));
        return batch;
    }

    private static TickPlanPlanner.Input plannerInput(JobDeclaration declaration) {
        return plannerInput(TickPlanStore.Control.of(TICK, PLAN - 1L, 1L, 1.0, 0L, 0L, 4.0,
            "normal"), List.of(declaration));
    }

    private static TickPlanPlanner.Input plannerInput(TickPlanStore.Control control,
                                                       List<JobDeclaration> declarations) {
        return new TickPlanPlanner.Input(TICK, PLAN, 1L, List.of(WORLD), List.of(DOMAIN),
            control, declarations, shareTable(), 64, TickPlanStore.Feedback.none());
    }

    private static ShareTable shareTable() {
        return new SharePlanner().plan(List.of(WORLD), TICK, Map.of());
    }

    private static JobDeclaration ownDeclaration() {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(WORLD,
            WriteLevel.REGION, DOMAIN, HolderKind.REGISTERED, HOLDER, 40);
        return new JobDeclaration(DOMAIN + "/" + WORLD + "/" + MAILBOX, 1L, WORLD, DOMAIN, 0,
            List.of(), 0, MAILBOX, "world:" + WORLD, List.of(ref), List.of(ref),
            ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand),
            new JobDeclaration.WriteRange(JobDeclaration.RangeGranularity.SECTION,
                new long[] {SectionPos.asLong(0, 4, 0)}));
    }

    private static PipelineWriteClaims.Report report(long planSequence, int position,
                                                      long lifecycle) {
        return new PipelineWriteClaims.Report(WORLD, DOMAIN, DOMAIN + "/" + WORLD + "/" + MAILBOX,
            planSequence, position, lifecycle, 7L, 4242L, 1);
    }

    private static TickPlan plan(long planSequence, String... nodeKeys) {
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        for (int index = 0; index < nodeKeys.length; index++) {
            steps.add(new TickPlan.CommitStep(index, WORLD, DOMAIN, nodeKeys[index],
                planSequence, 0, 1, false));
        }
        return new TickPlan(TICK, planSequence, 1L, List.of(WORLD), null, List.of(), steps, null,
            List.of(), 0, 0, 0L, null);
    }

    private static TickPlan plan(long planSequence, int position) {
        List<TickPlan.CommitStep> order = List.of(new TickPlan.CommitStep(position, WORLD, DOMAIN,
            DOMAIN + "/" + WORLD + "/" + MAILBOX, planSequence, 0, 1, false));
        return new TickPlan(planSequence, planSequence, 1L, List.of(WORLD), null, List.of(), order,
            null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    private static CommitLog commitLog(TickPlanStore store) {
        return new CommitLog(() -> 8, commitOrder(store));
    }

    private static CommitLog.OrderSource commitOrder(TickPlanStore store) {
        return new CommitLog.OrderSource() {

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
        };
    }

    private static void assertRefused(CommitLog log, CommitLog.Verdict verdict) {
        assertEquals(CommitLog.Entry.Disposition.DROPPED, verdict.disposition());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, verdict.code());
        assertEquals(0L, log.accepted());
    }

    private static CommitLog.Verdict reach(CommitLog log, PipelineWriteClaims.Report report) {
        return log.reach(new CommitLog.Batch(report.worldId(), report.domainId(),
            report.nodeKey(), CommitLog.Batch.Kind.APPLY, report.expectedVersion(),
            report.writeSetDigest(), report.rows()), report.planSequence(), report.position(),
            report.lifecycle());
    }

    private static DemandShadowCandidates.TaskStatus statusOf(
        PipelineBatchLedger.TrackedTask task, PipelineBatchLedger.BatchId batch) {
        PipelineBatchLedger.TaskId id = task.id();
        return new DemandShadowCandidates.TaskStatus("full", new DemandShadowCandidates.TaskStamp(
            id.ordinal(), id.worldId(), id.mailbox(), id.cell(), id.mintTick(),
            batch.freezeOrdinal(), batch.planSequence(), 77L));
    }

    @SuppressWarnings("unchecked")
    private static void setState(PipelineBatchLedger.TrackedTask task,
                                 PipelineBatchLedger.TaskState state) {
        try {
            Field field = PipelineBatchLedger.TrackedTask.class.getDeclaredField("state");
            field.setAccessible(true);
            ((AtomicReference<PipelineBatchLedger.TaskState>) field.get(task)).set(state);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setForming(PipelineBatchLedger ledger, long freezeOrdinal,
                                   boolean forming) {
        try {
            Field field = PipelineBatchLedger.class.getDeclaredField("forming");
            field.setAccessible(true);
            Set<Long> marks = (Set<Long>) field.get(ledger);
            if (forming) {
                marks.add(freezeOrdinal);
            } else {
                marks.remove(freezeOrdinal);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The claim source a deployment would wire: which world a level is, and which plan step the
     * claim holds. Both answers can be moved to drive the terms. */
    private static final class FixtureSource implements WriteControlledSlots.Source {

        private String world = WORLD;
        private WriteControlledSlots.Source.Step step;

        @Override
        public String worldOf(Object levelRef) {
            return LEVEL == levelRef ? world : "";
        }

        @Override
        public WriteControlledSlots.Source.Step stepOf(String worldId, String domainId,
                                                       String nodeKey) {
            return step == null || !WORLD.equals(worldId) || !DOMAIN.equals(domainId)
                ? null : step;
        }
    }

    /** The real sorter and mailbox of one test; the executors are stepped by hand. */
    private static final class SorterRig {

        private final ManualExecutor sorterExecutor = new ManualExecutor();
        private final ManualExecutor mainExecutor = new ManualExecutor();
        private final ProcessorMailbox<Runnable> mailbox;
        private final ChunkTaskPriorityQueueSorter sorter;
        private final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> queue;
        private final PipelineBatchLedger ledger;

        private SorterRig(PipelineBatchLedger ledger, boolean install) {
            this.ledger = ledger;
            mainExecutor.defer();
            mailbox = ProcessorMailbox.create(mainExecutor, MAILBOX);
            sorter = new ChunkTaskPriorityQueueSorter(List.of(mailbox), sorterExecutor, 8);
            queue = sorter.getProcessor(mailbox, true);
            sorterExecutor.defer();
            PrtsPipelineRows.bindMailboxWorlds(Map.of(mailbox, WORLD));
            if (install) {
                PrtsPipelineBatches.install(ledger, () -> TICK);
            }
        }

        private void offer(Function<ProcessorHandle<Unit>, Runnable> task) {
            queue.tell(ChunkTaskPriorityQueueSorter.message(task, CELL, () -> 1));
        }

        private void drainSorter() {
            sorterExecutor.drainOne();
        }

        private void drainMain() {
            mainExecutor.drainOne();
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
