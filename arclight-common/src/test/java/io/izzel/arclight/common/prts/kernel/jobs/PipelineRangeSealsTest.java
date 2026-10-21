/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.PipelineRangeSeals;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.RangeDigests;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seal factory, driven through the real run-start window: the real ledger cuts the batch, the
 * real demand bridge reads the status, the real plan store answers the seat and the real table takes
 * the seal. Nothing is started beyond these objects. */
class PipelineRangeSealsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final String DOMAIN = "chunk-pipeline";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long CELL2 = ChunkPos.asLong(4, -7);
    private static final long EPOCH = 5L;
    private static final String LIFECYCLE = "host/1/0";
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L);

    private PipelineBatchLedger ledger;
    private DemandShadowCandidates candidates;
    private TickPlanStore plans;
    private RangeSealTable table;
    private DeclarationInstanceAllocator instances;
    private FrozenDeclarations declarations;
    private PipelineRangeSeals factory;

    @BeforeEach
    void setUp() {
        ledger = new PipelineBatchLedger(64, 1000);
        candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
        plans = new TickPlanStore(8);
        table = new RangeSealTable(ledger.pendingLimit());
        instances = new DeclarationInstanceAllocator();
        declarations = FrozenDeclarations.of(FREEZE, PLAN, List.of()).declarations();
        factory = new PipelineRangeSeals(ledger, table, plans, instances::mintOne,
            world -> EPOCH, () -> declarations);
        PipelineRunSeal.installRange(factory);
    }

    @AfterEach
    void tearDown() {
        PipelineRunSeal.installRange(null);
        PipelineRunSeal.install(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    private PipelineBatchLedger.TrackedTask submit(long cell) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, cell, TICK);
        ledger.enqueued(task);
        return task;
    }

    private PipelineBatchLedger.BatchId freeze(long plan) {
        return ledger.freeze(WORLD, MAILBOX, plan, TICK);
    }

    private static void remember(PipelineRangeSeals factory, FrozenDeclarations declarations) {
        try {
            java.lang.reflect.Method method = PipelineRangeSeals.class.getDeclaredMethod(
                "rememberDeclarations", FrozenDeclarations.class);
            method.setAccessible(true);
            method.invoke(factory, declarations);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void retain(PipelineRangeSeals factory,
                               java.util.function.Predicate<PipelineBatchLedger.BatchId> retained) {
        try {
            java.lang.reflect.Method method = PipelineRangeSeals.class.getDeclaredMethod("retained",
                java.util.function.Predicate.class);
            method.setAccessible(true);
            method.invoke(factory, retained);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static TickPlan plan(long planSequence, String... nodeKeys) {
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        for (int index = 0; index < nodeKeys.length; index++) {
            steps.add(new TickPlan.CommitStep(index, WORLD, DOMAIN, nodeKeys[index], 1L, 0, 1,
                false));
        }
        return new TickPlan(TICK, planSequence, 1L, List.of(WORLD), null, List.of(), steps, null,
            List.of(), 0, 0, 0L, null);
    }

    private void publishPlan(PipelineBatchLedger.BatchId batch) {
        plans.publish(plan(batch.planSequence(), PipelineRoundJobs.batchKeyOf(batch)));
    }

    private void runBody(PipelineBatchLedger.TrackedTask task, Runnable body) {
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> body::run;
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PipelineBatchWrapper.function(ledger, task, delegate);
        Runnable produced = wrapped.apply(null);
        assertNotNull(produced);
        produced.run();
    }

    private long demand(PipelineBatchLedger.TrackedTask task, String status) {
        return PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(task.id().cell()),
            ChunkPos.getZ(task.id().cell()), status, true, task);
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

    private static DemandShadowCandidates.TaskStatus statusOf(PipelineBatchLedger.TrackedTask task,
                                                              PipelineBatchLedger.BatchId batch) {
        PipelineBatchLedger.TaskId id = task.id();
        return new DemandShadowCandidates.TaskStatus("full", new DemandShadowCandidates.TaskStamp(
            id.ordinal(), id.worldId(), id.mailbox(), id.cell(), id.mintTick(),
            batch.freezeOrdinal(), batch.planSequence(), 77L));
    }

    @Test
    void oneTaskSealsTheWholeBatchRangeAndOnlyItsOwnCell() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        AtomicReference<Integer> publishedInWindow = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
            publishedInWindow.set(table.size());
        });
        FrozenDeclarations.RangeSeal seal = seen.get();

        assertNotNull(seal);
        assertTrue(seal.wellFormed());
        assertEquals(WORLD, seal.worldId());
        assertEquals(MAILBOX, seal.mailbox());
        assertEquals(FREEZE, seal.freeze());
        assertEquals(batch.freezeOrdinal(), seal.freezeOrdinal());
        assertEquals(batch.planSequence(), seal.planSequence());
        assertEquals(PLAN, seal.seat().planSequence());
        assertNotEquals(0L, seal.instanceId());
        assertEquals(EPOCH, seal.worldEpoch());
        assertEquals(1, seal.cells().length);
        assertEquals(CELL, seal.cells()[0]);
        assertEquals(1, seal.statuses().size());
        assertNotNull(seal.statusFor(CELL));
        assertEquals("full", seal.statusFor(CELL).targetStatus());
        assertEquals(seal.spaceDigest(), RangeDigests.spaceDigest(seal.cells()));
        assertEquals(seal.statusMapDigest(), RangeDigests.statusMapDigest(seal.statuses()));
        assertEquals(task.id().ordinal(), seal.stamp().taskOrdinal());
        assertEquals(WORLD, seal.stamp().world());
        assertEquals(MAILBOX, seal.stamp().mailbox());
        assertEquals(CELL, seal.stamp().cell());
        assertEquals(TICK, seal.stamp().mintTick());
        assertEquals(seal.freezeOrdinal(), seal.stamp().batchFreezeOrdinal());
        assertEquals(PLAN, seal.stamp().planSequence());
        assertTrue(seal.stamp().demandToken() > 0L);
        assertEquals(1, publishedInWindow.get(), "the seal is in the table while the window runs");
        assertEquals(0, table.size(), "the window close releases an unpinned seal");
        assertEquals(1L, table.published());
        assertEquals(1L, table.releasals());
    }

    @Test
    void theSingleCellBatchIsALegalShapeAndTheRangeNamesIt() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        AtomicReference<long[]> cells = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
            cells.set(ledger.cellsOf(batch));
        });

        assertNotNull(seen.get());
        assertEquals(1, seen.get().cells().length);
        assertEquals(1, cells.get().length);
        assertEquals(1, seen.get().statuses().size());
    }

    @Test
    void aSixteenCellBatchSealsEveryCellInAscendingOrder() {
        List<PipelineBatchLedger.TrackedTask> tasks = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            tasks.add(submit(ChunkPos.asLong(index, 0)));
        }
        PipelineBatchLedger.TrackedTask first = tasks.get(0);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(first, () -> {
            demand(first, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        FrozenDeclarations.RangeSeal seal = seen.get();
        assertNotNull(seal);
        assertEquals(16, seal.cells().length);
        long[] cells = seal.cells();
        for (int index = 1; index < cells.length; index++) {
            assertTrue(cells[index] > cells[index - 1], "the range is ascending");
        }
        WriteControlledSlots.WriteSpace space = WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, cells);
        assertEquals(space.digest(), seal.spaceDigest(),
            "the range fold is the write-range fold");
    }

    @Test
    void twoTasksOfTwoCellsShareTheBatchIdentityAndSealTheirOwnCells() {
        PipelineBatchLedger.TrackedTask firstTask = submit(CELL);
        PipelineBatchLedger.TrackedTask secondTask = submit(CELL2);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> first = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> second = new AtomicReference<>();
        AtomicReference<Integer> firstSize = new AtomicReference<>();
        AtomicReference<Integer> secondSize = new AtomicReference<>();
        runBody(firstTask, () -> {
            demand(firstTask, "full");
            PipelineRunSeal.current().status();
            first.set(PipelineRunSeal.current().range());
            firstSize.set(table.size());
        });
        runBody(secondTask, () -> {
            demand(secondTask, "features");
            PipelineRunSeal.current().status();
            second.set(PipelineRunSeal.current().range());
            secondSize.set(table.size());
        });

        assertNotNull(first.get());
        assertNotNull(second.get());
        assertEquals(1, firstSize.get());
        assertEquals(1, secondSize.get(), "the first seal was released by its window");
        assertEquals(2L, table.published());
        assertEquals(2L, table.releasals());
        assertEquals(2, first.get().cells().length, "the batch range is carried whole");
        assertEquals(2, second.get().cells().length);
        assertEquals(first.get().cells()[0], CELL);
        assertEquals(first.get().cells()[1], CELL2);
        assertEquals(second.get().cells()[0], CELL);
        assertEquals(second.get().cells()[1], CELL2);
        assertEquals(1, first.get().statuses().size());
        assertEquals(1, second.get().statuses().size());
        assertEquals("full", first.get().statusFor(CELL).targetStatus());
        assertEquals("features", second.get().statusFor(CELL2).targetStatus());
        assertNotEquals(first.get().instanceId(), second.get().instanceId());
        assertEquals(firstTask.id().ordinal(), first.get().stamp().taskOrdinal());
        assertEquals(secondTask.id().ordinal(), second.get().stamp().taskOrdinal());
        assertEquals(first.get().freezeOrdinal(), second.get().freezeOrdinal());
        assertEquals(first.get().planSequence(), second.get().planSequence());
    }

    @Test
    void theSmallestOrdinalOwnsOneCellAndTheOtherNeverPublishesASecondSeal() {
        PipelineBatchLedger.TrackedTask owner = submit(CELL);
        PipelineBatchLedger.TrackedTask other = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertTrue(other.id().ordinal() > owner.id().ordinal());
        AtomicReference<FrozenDeclarations.RangeSeal> otherSeal = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> secondPresent = new AtomicReference<>();
        AtomicReference<DemandShadowCandidates.TaskStatus> otherStatus = new AtomicReference<>();
        runBody(other, () -> {
            demand(other, "full");
            otherStatus.set(PipelineRunSeal.current().status());
            otherSeal.set(PipelineRunSeal.current().range());
            secondPresent.set(factory.seal(other, otherStatus.get(), factory.generation()));
        });

        assertNull(otherSeal.get(), "a non owner task publishes no seal");
        assertNull(secondPresent.get(), "a second present of the same task publishes none either");
        assertEquals(0, table.size());
        assertTrue(factory.refused() >= 1L);

        AtomicReference<FrozenDeclarations.RangeSeal> ownerSeal = new AtomicReference<>();
        AtomicReference<Integer> ownerSize = new AtomicReference<>();
        runBody(owner, () -> {
            demand(owner, "full");
            PipelineRunSeal.current().status();
            ownerSeal.set(PipelineRunSeal.current().range());
            ownerSize.set(table.size());
        });
        assertNotNull(ownerSeal.get());
        assertEquals(owner.id().ordinal(), ownerSeal.get().stamp().taskOrdinal());
        assertEquals(1, ownerSize.get());
        assertEquals(1L, table.published());
        assertEquals(0, table.size(), "the window released the only seal");
    }

    @Test
    void aStampOfAnotherBatchOrCellSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> forged = new AtomicReference<>();
        runBody(task, () -> {
            PipelineBatchLedger.TaskId id = task.id();
            DemandShadowCandidates.TaskStatus wrongMailbox =
                new DemandShadowCandidates.TaskStatus("full", new DemandShadowCandidates.TaskStamp(
                    id.ordinal(), id.worldId(), "another-mailbox", id.cell(), id.mintTick(),
                    batch.freezeOrdinal(), batch.planSequence(), 77L));
            forged.set(factory.seal(task, wrongMailbox, factory.generation()));
        });

        assertNull(forged.get());
        assertEquals(0, table.size());
    }

    @Test
    void aPlanThatDoesNotCarryTheBatchSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            plans.publish(plan(PLAN, "chunk-pipeline/" + WORLD + "/" + MAILBOX + "#other"));
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });
        assertNull(seen.get(), "a plan that does not carry this batch publishes no seal");
        assertEquals(0, table.size());

        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId secondBatch = freeze(PLAN);
        publishPlan(secondBatch);
        AtomicReference<FrozenDeclarations.RangeSeal> afterReset = new AtomicReference<>();
        runBody(second, () -> {
            demand(second, "full");
            plans.reset();
            PipelineRunSeal.current().status();
            afterReset.set(PipelineRunSeal.current().range());
        });
        assertNull(afterReset.get(), "a plan of another lifecycle publishes no seal");
        assertEquals(0, table.size());
    }

    @Test
    void theRankIsThePlansOwnRanksAndMustAgreeWithTheSealedEntry() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        String nodeKey = PipelineRoundJobs.batchKeyOf(batch);
        String earlier = "chunk-pipeline/" + WORLD + "/aaa";
        plans.publish(plan(PLAN, earlier, nodeKey));
        int role = 2;
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 1);
        FrozenDeclarations.Entry entry = new FrozenDeclarations.Entry(seat, nodeKey, role, CELL,
            "full", 41L, EPOCH);
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(FREEZE, PLAN, List.of(entry));
        assertTrue(sealed.sealed());
        PipelineRunSeal.installRange(new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> sealed.declarations()));
        AtomicReference<FrozenDeclarations.RangeSeal> agreeing = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            agreeing.set(PipelineRunSeal.current().range());
        });
        assertNotNull(agreeing.get());
        assertEquals(role, agreeing.get().role());

        PipelineBatchLedger.TrackedTask other = submit(CELL2);
        PipelineBatchLedger.BatchId otherBatch = freeze(PLAN);
        String otherNode = PipelineRoundJobs.batchKeyOf(otherBatch);
        plans.publish(plan(PLAN, earlier, otherNode));
        FrozenDeclarations.Seat otherSeat = new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 1);
        FrozenDeclarations.Entry disagreeing = new FrozenDeclarations.Entry(otherSeat, otherNode, 1,
            CELL2, "full", 42L, EPOCH);
        FrozenDeclarations.Sealed otherTable = FrozenDeclarations.of(FREEZE, PLAN,
            List.of(disagreeing));
        PipelineRunSeal.installRange(new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> otherTable.declarations()));
        AtomicReference<FrozenDeclarations.RangeSeal> refused = new AtomicReference<>();
        runBody(other, () -> {
            demand(other, "full");
            PipelineRunSeal.current().status();
            refused.set(PipelineRunSeal.current().range());
        });
        assertNull(refused.get(), "an entry whose rank disagrees refuses the seal");
    }

    @Test
    void aDeadWorldGenerationSealsNothing() {
        PipelineRunSeal.installRange(new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> 0L, () -> declarations));
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get());
        assertEquals(0, table.size());
    }

    @Test
    void aPlanThatMovedOnBeforeTheStatusSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            ledger.freeze(WORLD, MAILBOX, PLAN + 1L, TICK);
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get(), "a batch the current plan replaced publishes no seal");
        assertEquals(0, table.size());
    }

    @Test
    void aRetainedOldPlanCanStillSealItsLateWindow() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        remember(factory, declarations);
        retain(factory, retained -> retained.equals(batch));
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();

        runBody(task, () -> {
            demand(task, "full");
            ledger.freeze(WORLD, MAILBOX, PLAN + 1L, TICK);
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNotNull(seen.get(), "a retained claim keeps its old plan's seal path");
        assertEquals(PLAN, seen.get().planSequence());
        assertEquals(1, factory.seals());
    }

    @Test
    void aTableResetInsideTheWindowSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            table.reset();
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get(), "a window of the previous generation publishes no seal");
        assertEquals(0, table.size());
        assertEquals(1L, factory.rejected());
    }

    @Test
    void aTaskNoFreezeHasCutSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        AtomicReference<FrozenDeclarations.RangeSeal> direct = new AtomicReference<>();
        runBody(task, () -> {
            PipelineBatchLedger.BatchId absent = new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 1L,
                PLAN);
            direct.set(factory.seal(task, statusOf(task, absent), factory.generation()));
        });

        assertNull(direct.get(), "a task whose state is not a running batch publishes no seal");
        assertEquals(0, table.size());
    }

    @Test
    void noInstanceNumberIsSpentWhenTheChecksRefuseBeforeTheMint() {
        PipelineRunSeal.installRange(new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> 0L, () -> declarations));
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        long before = instances.mintOne();
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get(), "the dead generation refuses the seal");
        assertEquals(before + 1L, instances.mintOne(),
            "a refusal before the mint spends no number");
        assertEquals(0, table.size());
    }

    @Test
    void anInstanceNumberSpentByAConflictIsNotHandedOutAgain() {
        RangeSealFixture blocker = new RangeSealFixture();
        blocker.world = WORLD;
        blocker.domain = DOMAIN;
        blocker.nodeKey = PipelineRoundJobs.batchKeyOf(
            new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 1L, PLAN));
        blocker.freeze(FREEZE);
        blocker.cells(CELL).cell(CELL).status("empty").at(PLAN, 0);
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(blocker.build()));
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        long before = instances.mintOne();
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get());
        assertEquals(1L, factory.conflicts());
        assertEquals(before + 2L, instances.mintOne(),
            "the number spent before a conflict is not handed out again");
        assertEquals(1, table.size(), "the blocking seal is still the one under the key");
    }

    @Test
    void anInstanceNumberSpentByAFullTableIsNotHandedOutAgain() {
        RangeSealTable small = new RangeSealTable(1);
        PipelineRangeSeals local = new PipelineRangeSeals(ledger, small, plans,
            instances::mintOne, world -> EPOCH, () -> declarations);
        PipelineRunSeal.installRange(local);
        RangeSealFixture filler = new RangeSealFixture();
        filler.freeze(FREEZE).cells(CELL2).cell(CELL2).at(PLAN, 1);
        assertEquals(RangeSealTable.Publish.PUBLISHED, small.publish(filler.build()));
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        long before = instances.mintOne();
        AtomicReference<FrozenDeclarations.RangeSeal> seen = new AtomicReference<>();
        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            seen.set(PipelineRunSeal.current().range());
        });

        assertNull(seen.get());
        assertEquals(1L, local.overflows());
        assertEquals(before + 2L, instances.mintOne(),
            "a number spent before a full table is not handed out again");
        assertEquals(1, small.size());
    }

    @Test
    void theOwnerAndItsCanonicalKeyAreTheSameWhicheverTaskRunsFirst() {
        FrozenDeclarations.RangeSeal nonOwnerFirst = ownerScenario(false);
        FrozenDeclarations.RangeSeal ownerFirst = ownerScenario(true);

        assertNotNull(nonOwnerFirst);
        assertNotNull(ownerFirst);
        assertEquals(CommitLog.logicalKeyOf(nonOwnerFirst).canonical(),
            CommitLog.logicalKeyOf(ownerFirst).canonical(),
            "the owner key does not depend on the order the two tasks present in");
        assertEquals(nonOwnerFirst.stamp().taskOrdinal(), ownerFirst.stamp().taskOrdinal());
        assertEquals(CELL, nonOwnerFirst.statuses().keySet().iterator().next());
        assertEquals(CELL, ownerFirst.statuses().keySet().iterator().next());
    }

    /** One fresh batch with two tasks on one cell; the flagged order drives their presents and the
     * owner seal is returned. */
    private static FrozenDeclarations.RangeSeal ownerScenario(boolean ownerPresentFirst) {
        PipelineBatchLedger local = new PipelineBatchLedger(64, 1000);
        PipelineBatchLedger.TrackedTask owner = local.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        local.enqueued(owner);
        PipelineBatchLedger.TrackedTask other = local.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        local.enqueued(other);
        PipelineBatchLedger.BatchId batch = local.freeze(WORLD, MAILBOX, PLAN, TICK);
        TickPlanStore localPlans = new TickPlanStore(8);
        localPlans.publish(plan(batch.planSequence(), PipelineRoundJobs.batchKeyOf(batch)));
        RangeSealTable localTable = new RangeSealTable(64);
        DeclarationInstanceAllocator localInstances = new DeclarationInstanceAllocator();
        PipelineRangeSeals localFactory = new PipelineRangeSeals(local, localTable, localPlans,
            localInstances::mintOne, world -> EPOCH,
            () -> FrozenDeclarations.of(FREEZE, PLAN, List.of()).declarations());
        AtomicReference<FrozenDeclarations.RangeSeal> ownerSeal = new AtomicReference<>();
        Runnable presentOwner = () -> {
            setState(owner, new PipelineBatchLedger.Running(batch));
            ownerSeal.set(localFactory.seal(owner, statusOf(owner, batch),
                localFactory.generation()));
        };
        Runnable presentOther = () -> {
            setState(other, new PipelineBatchLedger.Running(batch));
            assertNull(localFactory.seal(other, statusOf(other, batch), localFactory.generation()));
        };
        if (ownerPresentFirst) {
            presentOwner.run();
            presentOther.run();
        } else {
            presentOther.run();
            presentOwner.run();
        }
        assertEquals(1L, localFactory.seals());
        assertEquals(1, localTable.size());
        return ownerSeal.get();
    }

    @Test
    void aRealStatusIsRetainedAfterDoneUntilItsSettlementReleasesIt() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        runBody(task, () -> demand(task, "full"));

        assertTrue(task.state() instanceof PipelineBatchLedger.Done,
            "the body closed the task and the ledger moved it to Done");
        assertEquals(1, candidates.retainedCount(), "the status survives the reach of Done");
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();
        assertEquals(1, retained.size());
        DemandShadowCandidates.TaskStamp stamp = retained.get(0).stamp();
        assertNotNull(stamp);
        assertEquals(task.id().ordinal(), stamp.taskOrdinal());
        assertEquals(task.id().cell(), stamp.cell());
        assertEquals(batch.freezeOrdinal(), stamp.batchFreezeOrdinal());
        assertEquals(batch.planSequence(), stamp.planSequence());
        assertEquals("full", retained.get(0).status());

        assertEquals(1, candidates.releaseBatch(batch), "the settlement releases its batch");
        assertEquals(0, candidates.retainedCount());
        assertTrue(candidates.retainedStatuses().isEmpty());
    }

    @Test
    void aDeclaredLevelSealIsPublishedFromTheRetainedStatusAndMissingStatusPublishesNothing() {
        assertEquals(0, factory.publishDeclaredDeclared(declarations, List.of(),
            factory.generation()), "no retained status publishes no seal");
        assertEquals(0, table.size());

        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        runBody(task, () -> demand(task, "full"));
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();
        assertEquals(1, retained.size());

        int published = factory.publishDeclaredDeclared(declarations, retained,
            factory.generation());
        assertEquals(1, published, "the retained real status publishes the declared seat");
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 0);
        FrozenDeclarations.RangeSeal located = table.locate(seat, CELL);
        assertNotNull(located, "the seal is published under the plan's own seat");
        assertTrue(java.util.Arrays.binarySearch(located.cells(), CELL) >= 0);
        assertEquals("full", located.statusFor(CELL).targetStatus());

        int size = table.size();
        assertEquals(1, factory.publishDeclaredDeclared(declarations, retained,
            factory.generation()), "a second publish of the same seat is idempotent");
        assertEquals(size, table.size(), "idempotence does not grow the table");
        assertEquals(1, candidates.releaseBatch(batch));
        assertNotNull(table.locate(seat, CELL),
            "a published seal stays readable until a settlement or reset removes it");
    }

    @Test
    void theDefaultSettlementPublishesNothingAndMintsOncePerPublishedSeal() {
        assertEquals(0, factory.publishDeclaredDeclared(declarations, List.of(),
            factory.generation()), "no retained status publishes nothing");
        assertEquals(0, table.size());
        assertEquals(0L, factory.seals());

        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertTrue(ledger.runStart(task), "the ledger starts the task");
        demand(task, "full");
        ledger.runEnd(task);
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();
        assertEquals(1, factory.publishDeclaredDeclared(declarations, retained,
            factory.generation()));
        assertEquals(1, table.size());
        assertEquals(1L, factory.seals());
        assertEquals(2L, instances.mintOne(),
            "one published seal spends exactly one instance number");
    }

    @Test
    void aStaleGenerationPublishesNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertTrue(ledger.runStart(task), "the ledger starts the task");
        demand(task, "full");
        ledger.runEnd(task);
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();
        long generation = factory.generation();
        assertEquals(1, factory.publishDeclaredDeclared(declarations, retained, generation));
        long before = table.size();
        table.reset();
        assertEquals(0, factory.publishDeclaredDeclared(declarations, retained, generation),
            "a stale generation publishes nothing");
        assertEquals(before - 1, table.size(), "the reset cleared the old lifecycle's seal");
        assertEquals(1L, factory.rejected(), "the stale-generation attempt is counted as rejected");
    }

    @Test
    void onlyTheOwnerCellPublishesWhenTwoTasksShareIt() {
        PipelineBatchLedger.TrackedTask owner = submit(CELL);
        PipelineBatchLedger.TrackedTask other = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertTrue(ledger.runStart(owner));
        demand(owner, "full");
        ledger.runEnd(owner);
        assertTrue(ledger.runStart(other));
        demand(other, "full");
        ledger.runEnd(other);
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();
        assertEquals(2, retained.size(), "both tasks named a status");

        assertEquals(1, factory.publishDeclaredDeclared(declarations, retained,
            factory.generation()), "one cell has one owner seal");
        assertEquals(1, table.size());
    }

    @Test
    void aMissingInstanceMintsNoSeal() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertTrue(ledger.runStart(task));
        demand(task, "full");
        ledger.runEnd(task);
        List<DemandShadowCandidates.TaskStatus> retained = candidates.retainedStatuses();

        PipelineRangeSeals noInstance = new PipelineRangeSeals(ledger, table, plans,
            () -> 0L, world -> EPOCH, () -> declarations);
        assertEquals(0, noInstance.publishDeclaredDeclared(declarations, retained,
            noInstance.generation()), "a live instance is required before a seal is built");
        assertEquals(0, table.size());
    }

    @Test
    void aBindingThatFellOutOfTheHistorySealsNothing() {
        PipelineBatchLedger.BatchId first = null;
        for (int round = 0; round < 65; round++) {
            PipelineBatchLedger.TrackedTask task = submit(1000L + round);
            PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
            assertNotNull(batch);
            if (first == null) {
                first = batch;
                publishPlan(batch);
            }
            ledger.runStart(task);
            ledger.runEnd(task);
        }
        assertNotNull(first);
        assertEquals(0, ledger.cellsOf(first).length, "the binding of the first batch was evicted");

        PipelineBatchLedger.TrackedTask late = ledger.mint(new Object(), WORLD, MAILBOX, 1000L, TICK);
        setState(late, new PipelineBatchLedger.Running(first));
        FrozenDeclarations.RangeSeal seal = factory.seal(late, statusOf(late, first),
            factory.generation());

        assertNull(seal, "an evicted binding has no cells and seals nothing");
        assertEquals(0, table.size());
    }
}
