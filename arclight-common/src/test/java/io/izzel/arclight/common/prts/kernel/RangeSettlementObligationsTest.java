/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.commit.RangeSettlementObligations;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settlement obligations of the range seals: the real publish table hands out the pins, the real
 * registry settles them, and the real factory holds them across the real run-start window. The plan
 * generations are fixtures; every object that holds state is the production class. */
class RangeSettlementObligationsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final String DOMAIN = "chunk-pipeline";
    private static final long TICK = 100L;
    private static final long PLAN = 9L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long EPOCH = 5L;
    private static final String LIFECYCLE = "host/1/0";
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L);

    private RangeSealTable table;
    private RangeSettlementObligations registry;

    @BeforeEach
    void setUp() {
        table = new RangeSealTable(16);
        registry = new RangeSettlementObligations();
    }

    @AfterEach
    void tearDown() {
        PipelineRunSeal.installRange(null);
        PipelineRunSeal.install(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    private static FrozenDeclarations.RangeSeal seal(long plan, long cell) {
        RangeSealFixture fixture = new RangeSealFixture();
        return fixture.cells(cell).cell(cell).at(plan, 1).build();
    }

    private FrozenDeclarations.RangeSeal published(long plan, long cell) {
        return published(plan, 1, cell);
    }

    private FrozenDeclarations.RangeSeal published(long plan, int position, long cell) {
        RangeSealFixture fixture = new RangeSealFixture();
        FrozenDeclarations.RangeSeal seal =
            fixture.cells(cell).cell(cell).at(plan, position).build();
        assertEquals(RangeSealTable.Publish.PUBLISHED, table.publish(seal));
        return seal;
    }

    private boolean holdOnce(FrozenDeclarations.RangeSeal seal) {
        return registry.holds(seal) || registry.hold(seal, table.oblige(seal));
    }

    @Test
    void aHeldSealIsSettledOnceByItsSeatAndTheReleasedSealLeaves() {
        FrozenDeclarations.RangeSeal seal = published(PLAN, CELL);
        assertTrue(holdOnce(seal));
        assertTrue(registry.holds(seal));
        assertEquals(1, registry.heldCount());
        assertEquals(1, table.pinnedCount());
        table.release(seal);
        assertEquals(1, table.size(), "a released seal its pin still names stays readable");

        assertEquals(1, registry.settleSeat(seal.seat()));
        assertEquals(0, table.size(), "the last settlement removes the released seal");
        assertEquals(0, table.pinnedCount());
        assertEquals(0, registry.heldCount());
        assertEquals(1L, registry.settled());
        assertEquals(0, registry.settleSeat(seal.seat()), "settling an unknown place settles nothing");
    }

    @Test
    void aRepeatedHoldDoesNotPinTheSameSealATwice() {
        FrozenDeclarations.RangeSeal seal = published(PLAN, CELL);
        IdentityWitness.Obligation pin = table.oblige(seal);
        assertTrue(registry.hold(seal, pin));
        assertFalse(registry.hold(seal, pin), "the place is held already");
        assertTrue(holdOnce(seal), "the guarded caller takes no second pin either");
        assertEquals(1, table.pinnedCount());
        assertEquals(1, registry.heldCount());
        registry.settleSeat(seal.seat());
        assertEquals(0, table.pinnedCount());
    }

    @Test
    void settleSeatLeavesTheObligationsOfAnotherSeatAlone() {
        FrozenDeclarations.RangeSeal first = published(PLAN, CELL);
        FrozenDeclarations.RangeSeal second = published(PLAN, 2, RangeSealFixture.CELL2);
        assertTrue(holdOnce(first));
        assertTrue(holdOnce(second));

        assertEquals(1, registry.settleSeat(first.seat()));
        assertEquals(1, registry.heldCount());
        assertEquals(1, table.pinnedCount());
        assertFalse(registry.holds(first));
        assertTrue(registry.holds(second));
    }

    @Test
    void settleBeforeSettlesOnlyThePlansOlderThanTheGivenOne() {
        FrozenDeclarations.RangeSeal older = published(PLAN, CELL);
        FrozenDeclarations.RangeSeal newer = published(PLAN + 1L, RangeSealFixture.CELL2);
        assertTrue(holdOnce(older));
        assertTrue(holdOnce(newer));

        assertEquals(1, registry.settleBefore(PLAN + 1L));
        assertFalse(registry.holds(older));
        assertTrue(registry.holds(newer), "the plan the settlement period is in is not swept");
        assertEquals(0, registry.settleBefore(PLAN + 1L), "the sweep is idempotent");
        assertEquals(1, registry.settleBefore(PLAN + 2L));
        assertEquals(0, registry.heldCount());
        assertEquals(0, table.pinnedCount());
    }

    @Test
    void aResetDropsTheEntriesAndASettlementAfterItIsANoOp() {
        FrozenDeclarations.RangeSeal seal = published(PLAN, CELL);
        assertTrue(holdOnce(seal));
        table.reset();
        registry.reset();

        assertEquals(0, registry.heldCount());
        assertEquals(0, table.pinnedCount());
        assertEquals(0, registry.settleSeat(seal.seat()), "the table no longer holds the seal");
        assertEquals(1L, registry.discarded());
        assertEquals(0L, registry.settled());
        assertEquals(0, table.size(), "a reset entry revives no seal");
    }

    @Test
    void aSettlementOfAnUnknownPlaceSettlesNothingAndDoesNotThrow() {
        FrozenDeclarations.Seat unknown = new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 4);
        assertEquals(0, registry.settleSeat(unknown));
        assertEquals(0, registry.settleSeat(null));
        assertEquals(0, registry.settleBefore(0L));
        assertEquals(0, registry.heldCount());
    }

    @Test
    void aHoldWithoutAPinIsCountedAndNotRegistered() {
        FrozenDeclarations.RangeSeal absent = seal(PLAN, CELL);

        assertFalse(registry.hold(absent, table.oblige(absent)));
        assertEquals(0, registry.heldCount());
        assertEquals(1L, registry.unbacked());
        assertFalse(registry.hold(null, table.oblige(absent)));
        assertFalse(registry.hold(absent, null));
    }

    @Test
    void aSettlementThatThrowsKeepsItsEntryAndIsRetried() {
        FrozenDeclarations.RangeSeal seal = published(PLAN, CELL);
        AtomicBoolean refused = new AtomicBoolean(true);
        IdentityWitness.Obligation throwing = () -> {
            if (refused.compareAndSet(true, false)) {
                throw new IllegalStateException("settlement refused");
            }
        };
        assertTrue(registry.hold(seal, throwing));

        assertEquals(0, registry.settleSeat(seal.seat()));
        assertEquals(1L, registry.settleFailures(), "the failure is counted, never swallowed");
        assertEquals(1, registry.heldCount(), "the entry still owes its settlement");
        assertEquals(1, registry.settleSeat(seal.seat()), "the retry settles it");
        assertEquals(0, registry.heldCount());
        assertEquals(1L, registry.settled());
    }

    @Test
    void theHeldKeysAreAReadOnlySnapshot() {
        FrozenDeclarations.RangeSeal seal = published(PLAN, CELL);
        assertTrue(holdOnce(seal));
        List<RangeSealTable.Key> keys = registry.heldKeys();
        assertEquals(1, keys.size());
        assertEquals(seal.seat(), keys.get(0).seat());
        assertEquals(CELL, keys.get(0).cell());
        assertThrows(UnsupportedOperationException.class,
            () -> keys.add(new RangeSealTable.Key(FREEZE, seal.seat(), CELL)));
    }

    /** The window harness a case drives: the factory it installed and the task whose body publishes. */
    private record Drive(PipelineRangeSeals factory, PipelineBatchLedger ledger,
                         PipelineBatchLedger.TrackedTask task) {
    }

    private Drive armedDrive() {
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        DemandShadowCandidates candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
        TickPlanStore plans = new TickPlanStore(8);
        DeclarationInstanceAllocator instances = new DeclarationInstanceAllocator();
        FrozenDeclarations declarations = FrozenDeclarations.of(FREEZE, PLAN, List.of()).declarations();
        PipelineRangeSeals factory = new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> declarations, registry);
        PipelineRunSeal.installRange(factory);
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        steps.add(new TickPlan.CommitStep(0, WORLD, DOMAIN,
            PipelineRoundJobs.batchKeyOf(batch), 1L, 0, 1, false));
        plans.publish(new TickPlan(TICK, PLAN, 1L, List.of(WORLD), null, List.of(), steps, null,
            List.of(), 0, 0, 0L, null));
        return new Drive(factory, ledger, task);
    }

    private static void runBody(PipelineBatchLedger ledger, PipelineBatchLedger.TrackedTask task,
                                Runnable body) {
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> body::run;
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PipelineBatchWrapper.function(ledger, task, delegate);
        Runnable produced = wrapped.apply(null);
        assertNotNull(produced);
        produced.run();
    }

    private static void demand(PipelineBatchLedger.TrackedTask task, String status) {
        PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(task.id().cell()),
            ChunkPos.getZ(task.id().cell()), status, true, task);
    }

    @Test
    void aPublishedSealIsPinnedAcrossTheWindowAndSettledAfterIt() {
        Drive drive = armedDrive();
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();
        runBody(drive.ledger, drive.task, () -> {
            demand(drive.task, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
        });

        FrozenDeclarations.RangeSeal seal = sealed.get();
        assertNotNull(seal);
        assertTrue(drive.factory.settlementObligationsRegistered());
        assertEquals(1, registry.heldCount(), "the obligation outlives the window");
        assertEquals(1, table.size(), "the window close kept the pinned seal");
        assertEquals(1, table.pinnedCount());

        drive.factory.settleSeat(seal.seat());
        assertEquals(0, registry.heldCount());
        assertEquals(0, table.size(), "the settlement released the seal");
        assertEquals(0, table.pinnedCount());
    }

    @Test
    void theFactoryWithoutARegistryPinsNothing() {
        table = new RangeSealTable(16);
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        DemandShadowCandidates candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
        TickPlanStore plans = new TickPlanStore(8);
        DeclarationInstanceAllocator instances = new DeclarationInstanceAllocator();
        FrozenDeclarations declarations = FrozenDeclarations.of(FREEZE, PLAN, List.of()).declarations();
        PipelineRangeSeals local = new PipelineRangeSeals(ledger, table, plans,
            instances::mintOne, world -> EPOCH, () -> declarations);
        PipelineRunSeal.installRange(local);
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
        assertNotNull(batch);
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        steps.add(new TickPlan.CommitStep(0, WORLD, DOMAIN,
            PipelineRoundJobs.batchKeyOf(batch), 1L, 0, 1, false));
        plans.publish(new TickPlan(TICK, PLAN, 1L, List.of(WORLD), null, List.of(), steps, null,
            List.of(), 0, 0, 0L, null));
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();
        runBody(ledger, task, () -> {
            demand(task, "full");
            PipelineRunSeal.current().status();
            sealed.set(PipelineRunSeal.current().range());
        });

        assertNotNull(sealed.get());
        assertFalse(local.settlementObligationsRegistered(),
            "the default factory is handed no registry");
        assertEquals(0, registry.heldCount());
        assertEquals(0, table.pinnedCount());
        assertEquals(0, table.size(), "an unpinned seal leaves when its window closes");
        local.settleSeat(sealed.get().seat());
        local.settleBefore(PLAN + 1L);
        local.resetObligations();
        assertEquals(0, registry.heldCount(), "the unarmed calls settle nothing");
    }
}
