/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeDigests;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchWrapper;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRunSeal;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production wiring of the range seal factory: the real ledger cuts the batch, the real demand
 * bridge reads the one status, the real plan store answers the seat, the real table takes the seal
 * and the real run-start window carries it. The factory is the production class over the same
 * objects the module hands it; only the world, the plan and the instance source are fixtures. */
class PipelineRangeSealProductionWiringTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final String DOMAIN = "chunk-pipeline";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long CELL2 = ChunkPos.asLong(4, -7);
    private static final long CELL3 = ChunkPos.asLong(5, -7);
    private static final long EPOCH = 5L;
    private static final String LIFECYCLE = "host/1/0";
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L);

    private PipelineBatchLedger ledger;
    private DemandShadowCandidates candidates;
    private TickPlanStore plans;
    private RangeSealTable table;
    private DeclarationInstanceAllocator instances;
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
        factory = new PipelineRangeSeals(ledger, table, plans, instances::mintOne, world -> EPOCH,
            () -> FREEZE, IdentityWitness.NONE);
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

    @SuppressWarnings("unchecked")
    private void setForming(long freezeOrdinal, boolean forming) {
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

    private static DemandShadowCandidates.TaskStatus statusOf(PipelineBatchLedger.TrackedTask task,
                                                              PipelineBatchLedger.BatchId batch) {
        PipelineBatchLedger.TaskId id = task.id();
        return new DemandShadowCandidates.TaskStatus("full", new DemandShadowCandidates.TaskStamp(
            id.ordinal(), id.worldId(), id.mailbox(), id.cell(), id.mintTick(),
            batch.freezeOrdinal(), batch.planSequence(), 77L));
    }

    @Test
    void aPublishedBindingPublishesTheSealOverTheWholeRange() {
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        String earlier = DOMAIN + "/" + WORLD + "/aaa";
        plans.publish(plan(PLAN, earlier, PipelineRoundJobs.batchKeyOf(batch)));
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();
        AtomicReference<Integer> publishedInWindow = new AtomicReference<>();

        runBody(first, () -> {
            demand(first, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
            publishedInWindow.set(table.size());
        });

        FrozenDeclarations.RangeSeal seal = sealed.get();
        assertNotNull(seal);
        assertTrue(seal.wellFormed());
        assertArrayEquals(ledger.cellsOf(batch), seal.cells(),
            "the seal carries the cells of the published binding");
        assertEquals(2, seal.cells().length);
        assertEquals(1, seal.statuses().size());
        assertEquals(CELL, seal.statuses().keySet().iterator().next().longValue());
        assertEquals(second.id().cell(), seal.cells()[1]);
        assertEquals(2, seal.role(), "the rank is the plan's own rank, not the plan position");
        assertEquals(new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 1), seal.seat());
        assertEquals(batch.freezeOrdinal(), seal.freezeOrdinal());
        assertEquals(batch.planSequence(), seal.planSequence());
        assertEquals(FREEZE, seal.freeze());
        assertEquals(EPOCH, seal.worldEpoch());
        assertTrue(seal.instanceId() > 0L);
        assertEquals(first.id().ordinal(), seal.stamp().taskOrdinal());
        assertEquals(CELL, seal.stamp().cell());
        assertTrue(seal.stamp().demandToken() > 0L);
        assertEquals(seal.spaceDigest(), RangeDigests.spaceDigest(seal.cells()));
        assertEquals(seal.statusMapDigest(), RangeDigests.statusMapDigest(seal.statuses()));
        assertEquals(1, publishedInWindow.get(), "the seal is in the table while the window runs");
        assertEquals(0, table.size(), "the window close releases an unpinned seal");
    }

    @Test
    void aNonOwnerTaskPublishesNothingAndSpendsNoInstance() {
        PipelineBatchLedger.TrackedTask owner = submit(CELL);
        PipelineBatchLedger.TrackedTask other = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        long before = instances.mintOne();
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();

        runBody(other, () -> {
            demand(other, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
        });

        assertNull(sealed.get(), "only the smallest ordinal of a cell publishes a seal");
        assertEquals(0, table.size());
        assertTrue(factory.refused() >= 1L);
        assertEquals(before + 1L, instances.mintOne(),
            "a refusal before the mint spends no instance number");
        assertTrue(other.id().ordinal() > owner.id().ordinal());
    }

    @Test
    void aRunningTaskOfAnUnpublishedBindingPublishesNothing() {
        PipelineBatchLedger.TrackedTask first = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(first);
        PipelineBatchLedger.TrackedTask second = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(second);
        PipelineBatchLedger.BatchId forming = new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 500L,
            PLAN);
        setState(first, new PipelineBatchLedger.Claimed(forming, PipelineBatchLedger.Place.QUEUED));
        setState(second, new PipelineBatchLedger.Claimed(forming, PipelineBatchLedger.Place.QUEUED));
        setForming(forming.freezeOrdinal(), true);
        plans.publish(plan(PLAN, PipelineRoundJobs.batchKeyOf(forming)));
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN, TICK),
            "no pending task is claimable, so the freeze names the plan and publishes nothing");
        setState(first, new PipelineBatchLedger.Running(forming));
        long before = instances.mintOne();

        assertFalse(ledger.bindingPublished(forming));
        assertEquals(2, ledger.tasksOf(forming).size(),
            "the forming fallback can read members the published-binding answer refuses");
        assertEquals(-1L, ledger.ownerOrdinal(forming, CELL));
        FrozenDeclarations.RangeSeal sealed = factory.seal(first, statusOf(first, forming),
            factory.generation());

        assertNull(sealed, "a binding that is not published publishes no seal");
        assertEquals(0, table.size());
        assertTrue(factory.refused() >= 1L);
        assertEquals(before + 1L, instances.mintOne());
    }

    @Test
    void theTableCapacityIsTheLedgerPendingBound() {
        assertEquals(ledger.pendingLimit(), table.capacity());

        PipelineBatchLedger small = new PipelineBatchLedger(2, 1000);
        RangeSealTable sized = new RangeSealTable(small.pendingLimit());
        assertEquals(2, sized.capacity(), "the capacity is read from the ledger, never from a literal");
        assertEquals(2, small.pendingLimit());
    }

    @Test
    void aFullTableSealsNothingAndKeepsTheSealsAndPinsItHas() {
        RangeSealTable small = new RangeSealTable(1);
        PipelineRangeSeals local = new PipelineRangeSeals(ledger, small, plans,
            instances::mintOne, world -> EPOCH, () -> FREEZE, IdentityWitness.NONE);
        PipelineRunSeal.installRange(local);
        RangeSealFixture filler = new RangeSealFixture();
        filler.freeze(FREEZE).cells(CELL3).cell(CELL3).at(PLAN, 5);
        FrozenDeclarations.RangeSeal held = filler.build();
        assertEquals(RangeSealTable.Publish.PUBLISHED, small.publish(held));
        IdentityWitness.Obligation pin = small.oblige(held);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();

        runBody(task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
        });

        assertNull(sealed.get(), "a full table refuses the newcomer");
        assertEquals(1L, local.overflows());
        assertEquals(1, small.size(), "a full table keeps every seal it has");
        assertSame(held, small.locate(held.seat(), CELL3));
        assertEquals(1, small.pinnedCount(), "the pinned seal is not evicted by the full table");
        pin.settle();
        assertEquals(1, small.size());
    }

    @Test
    void aWindowCloseReleasesAnUnpinnedSealAndKeepsAPinnedOne() {
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<Integer> unpinnedSize = new AtomicReference<>();

        runBody(first, () -> {
            demand(first, "full");
            PipelineRunSeal.current().status();
            unpinnedSize.set(table.size());
        });

        assertEquals(1, unpinnedSize.get());
        assertEquals(0, table.size(), "an unpinned seal leaves when its window closes");

        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId secondBatch = freeze(PLAN);
        publishPlan(secondBatch);
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();
        AtomicReference<IdentityWitness.Obligation> pin = new AtomicReference<>();

        runBody(second, () -> {
            demand(second, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
            pin.set(table.oblige(sealed.get()));
        });

        assertNotNull(sealed.get());
        assertNotNull(pin.get());
        assertEquals(1, table.size(), "a pinned seal stays after its window closes");
        assertSame(sealed.get(), table.locate(sealed.get().seat(), CELL2));
        assertEquals(RangeSealTable.Publish.IDEMPOTENT, table.publish(sealed.get()),
            "the released seal is still the one under its key");
        assertEquals(1, table.pinnedCount());
        pin.get().settle();
        assertEquals(0, table.size(), "the last obligation settling removes the released seal");
        pin.get().settle();
        assertEquals(0, table.size(), "settling twice changes nothing");
    }

    @Test
    void aTableResetBetweenTheWindowAndTheSealRefusesTheOldGeneration() {
        RangeSealFixture filler = new RangeSealFixture();
        filler.freeze(FREEZE).cells(CELL3).cell(CELL3).at(PLAN, 5);
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(filler.build()));
        RangeSealTable.Snapshot before = table.snapshot();
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();

        runBody(task, () -> {
            demand(task, "full");
            table.reset();
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
        });

        assertNull(sealed.get(), "a window of the previous generation publishes no seal");
        assertEquals(0, table.size());
        assertEquals(1L, factory.rejected());
        assertEquals(1, before.seals().size(), "a snapshot is a value and survives the reset");
        assertEquals(before.digest(), before.recompute());
        assertNull(table.locate(filler.seat(), CELL3));
    }

    @Test
    void theResetAndSealInterleavingsLeaveNoOldSealReadable() {
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.BatchId firstBatch = freeze(PLAN);
        publishPlan(firstBatch);
        AtomicReference<FrozenDeclarations.RangeSeal> sealedFirst = new AtomicReference<>();

        runBody(first, () -> {
            demand(first, "full");
            PipelineRunSeal.current().status();
            sealedFirst.set(PipelineRunSeal.current().range());
            table.reset();
        });

        assertNotNull(sealedFirst.get());
        assertEquals(0, table.size(), "a seal published before a reset is not readable after it");
        assertNull(table.locate(sealedFirst.get().seat(), CELL));

        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId secondBatch = freeze(PLAN);
        publishPlan(secondBatch);
        table.reset();
        AtomicReference<FrozenDeclarations.RangeSeal> afterReset = new AtomicReference<>();

        runBody(second, () -> {
            demand(second, "full");
            PipelineRunSeal.current().status();
            afterReset.set(PipelineRunSeal.current().range());
        });

        assertNotNull(afterReset.get(), "a window opened after a reset seals under the new one");
        assertEquals(0, table.size());

        PipelineBatchLedger.TrackedTask third = submit(CELL3);
        PipelineBatchLedger.BatchId thirdBatch = freeze(PLAN);
        publishPlan(thirdBatch);
        AtomicReference<FrozenDeclarations.RangeSeal> refused = new AtomicReference<>();

        runBody(third, () -> {
            demand(third, "full");
            table.reset();
            PipelineRunSeal.current().status();
            refused.set(PipelineRunSeal.current().range());
        });

        assertNull(refused.get(), "a table reset before the seal refuses its captured generation");
        assertEquals(0, table.size());
        assertEquals(1L, factory.rejected());

        PipelineBatchLedger.TrackedTask fourth = submit(CELL3);
        PipelineBatchLedger.BatchId fourthBatch = freeze(PLAN);
        publishPlan(fourthBatch);
        ledger.reset();
        FrozenDeclarations.RangeSeal dead = factory.seal(fourth, statusOf(fourth, fourthBatch),
            factory.generation());

        assertNull(dead, "a task a reset detached publishes no seal");
        assertEquals(0, table.size());
        assertEquals(1L, factory.refused());
    }

    @Test
    void aLateRunOpensNoWindowAndSealsNothing() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        publishPlan(batch);
        assertNull(ledger.freeze(WORLD, MAILBOX, PLAN + 1L, TICK),
            "no pending task is claimable for the next plan");
        AtomicReference<PipelineRunSeal> window = new AtomicReference<>();
        AtomicReference<Integer> size = new AtomicReference<>();

        runBody(task, () -> {
            window.set(PipelineRunSeal.current());
            size.set(table.size());
        });

        assertNull(window.get(), "a batch the current plan replaced opens no run-start window");
        assertEquals(0, size.get());
        assertEquals(1L, ledger.lateRunCount());
        assertEquals(0L, factory.seals());
        assertEquals(0, table.size());
    }

    @Test
    void aBindingDroppedFromTheHistorySealsNothing() {
        PipelineBatchLedger.BatchId first = null;
        for (int round = 0; round < 65; round++) {
            PipelineBatchLedger.TrackedTask task = submit(1000L + round);
            PipelineBatchLedger.BatchId batch = freeze(PLAN);
            assertNotNull(batch);
            if (first == null) {
                first = batch;
            }
            assertTrue(ledger.runStart(task));
            ledger.runEnd(task);
        }
        assertNotNull(first);
        assertEquals(0, ledger.cellsOf(first).length, "the binding of the first batch was evicted");

        PipelineBatchLedger.TrackedTask late = ledger.mint(new Object(), WORLD, MAILBOX, 1000L, TICK);
        setState(late, new PipelineBatchLedger.Running(first));
        FrozenDeclarations.RangeSeal sealed = factory.seal(late, statusOf(late, first),
            factory.generation());

        assertNull(sealed, "an evicted binding carries no cells and seals nothing");
        assertEquals(0, table.size());
        assertFalse(ledger.bindingPublished(first));
    }
}
