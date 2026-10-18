/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The activation order of the typed consumer: the run-start window asks its one status, the seal
 * it may publish is pinned, the settlement that names its seat releases it, and the readiness of the
 * real module reads the wiring the batch installed. */
class TypedConsumerActivationTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String MAILBOX = "sorter";
    private static final long TICK = 100L;
    private static final long PLAN = 9L;
    private static final long FREEZE_ORDINAL = 3L;
    private static final long EPOCH = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey("host/1/0", 1L);

    private final RangeSettlementObligations registry = new RangeSettlementObligations();
    private final AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();

    @AfterEach
    void removeTheSeams() {
        PipelineRunSeal.installRange(null);
        PipelineRunSeal.install(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    @Test
    void theWindowAsksItsStatusBeforeItClosesAndTheSealIsPinned() {
        Drive drive = new Drive();

        drive.run(() -> demand(drive.task, "full"));

        FrozenDeclarations.RangeSeal seal = sealed.get();
        assertNotNull(seal, "the window asked its status and published the range");
        assertTrue(seal.wellFormed());
        assertEquals(1, drive.factory.seals());
        assertEquals(1, drive.table.size(), "the published seal outlives the window");
        assertEquals(1, drive.table.pinnedCount());
        assertEquals(1, registry.heldCount());

        drive.factory.settleSeat(seal.seat());

        assertEquals(0, registry.heldCount(), "the settlement of the seat ends the obligation");
        assertEquals(0, drive.table.pinnedCount());
        assertEquals(0, drive.table.size(), "the settled seal leaves the table");
    }

    @Test
    void aWindowWithoutAStatusPublishesNoSeal() {
        Drive drive = new Drive();

        drive.run(() -> {
        });

        assertNull(sealed.get(), "a window whose status is absent carries no range");
        assertEquals(0, drive.table.size());
        assertEquals(0, registry.heldCount());
    }

    @Test
    void aBodyThatThrowsStillAsksTheStatusAndPinsTheSeal() {
        Drive drive = new Drive();

        assertThrows(IllegalStateException.class, () -> drive.run(() -> {
            demand(drive.task, "full");
            throw new IllegalStateException("body refused");
        }));

        assertNotNull(sealed.get(), "the window asks its status even when the body failed");
        assertEquals(1, registry.heldCount());
        drive.factory.settleSeat(sealed.get().seat());
        assertEquals(0, drive.table.size());
    }

    @Test
    void aRunWithoutAnInstalledSourceAsksNothing() {
        Drive drive = new Drive();
        PipelineRunSeal.install(null);

        drive.run(() -> demand(drive.task, "full"));

        assertNull(sealed.get(), "no register means no run-start window at all");
        assertEquals(0, drive.table.size());
        assertEquals(0, registry.heldCount());
    }

    @Test
    void aSweepSettlesTheObligationOfAPlanTheSettlementHasPassed() {
        Drive drive = new Drive();
        drive.run(() -> demand(drive.task, "full"));
        FrozenDeclarations.RangeSeal seal = sealed.get();
        assertNotNull(seal);

        drive.factory.settleBefore(FREEZE_ORDINAL + 1L);

        assertEquals(1, registry.heldCount(), "the seat is of a plan the sweep has not passed");
        drive.factory.settleBefore(PLAN + 1L);
        assertEquals(0, registry.heldCount(), "a seal of an older plan is swept");
        assertEquals(0, drive.table.size());
    }

    @Test
    void theReadinessRecordReadsBothStatesOfTheBuild() {
        assertTrue(KernelModule.TypedConsumerReadiness.SETTLEMENT_WIRED);
        KernelModule.TypedConsumerReadiness bound =
            new KernelModule.TypedConsumerReadiness(true, true, true, true);
        assertTrue(bound.inputsBound());
        assertTrue(bound.ready(), "wired and every input held reads ready");
        KernelModule.TypedConsumerReadiness missing =
            new KernelModule.TypedConsumerReadiness(false, true, true, true);
        assertFalse(missing.ready());

        KernelModule module = KernelModule.instance();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.resetReadings();
        module.markSafePoint();
        try {
            KernelModule.TypedConsumerReadiness ready = module.typedConsumerReadiness();
            assertTrue(ready.attributionArmed(), "the production claim point carries the sink");
            assertTrue(ready.obligationsRegistered(), "the factory holds its seals");
            assertFalse(ready.effectsBound(), "the default build has no effect store");
            assertFalse(ready.controllerBound(), "the default build adopts no chain");
            assertFalse(ready.ready());
        } finally {
            module.resetReadings();
            module.markSafePoint();
        }
    }

    @Test
    void aResetClearsTheRangeTableAndEveryPinItHeld() {
        KernelModule module = KernelModule.instance();
        module.rollbackCommitChain();
        module.markSafePoint();
        module.resetReadings();
        module.markSafePoint();
        try {
            long generation = module.rangeSeals().generation();
            FrozenDeclarations.RangeSeal seal = new RangeSealFixture().build();
            assertEquals(RangeSealTable.Publish.PUBLISHED, module.rangeSeals().publish(seal));
            module.rangeSeals().oblige(seal);
            assertEquals(1, module.rangeSeals().pinnedCount());

            module.resetReadings();

            assertEquals(0, module.rangeSeals().size());
            assertEquals(0, module.rangeSeals().pinnedCount());
            assertEquals(generation + 1L, module.rangeSeals().generation());
        } finally {
            module.resetReadings();
            module.markSafePoint();
        }
    }

    private static void demand(PipelineBatchLedger.TrackedTask task, String status) {
        PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(task.id().cell()),
            ChunkPos.getZ(task.id().cell()), status, true, task);
    }

    /** The window harness of one case: the real ledger, the real run seal, the real factory with a
     * real settlement registry and the real wrapper. The recording sink keeps the seal the factory
     * published so a case can settle it. */
    private final class Drive {

        private final PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        private final DemandShadowCandidates candidates =
            new DemandShadowCandidates(ledger.pendingLimit());
        private final TickPlanStore plans = new TickPlanStore(8);
        private final RangeSealTable table = new RangeSealTable(ledger.pendingLimit());
        private final DeclarationInstanceAllocator instances = new DeclarationInstanceAllocator();
        private final FrozenDeclarations declarations =
            FrozenDeclarations.of(FREEZE, PLAN, List.of()).declarations();
        private final PipelineRangeSeals factory;
        private final PipelineBatchLedger.TrackedTask task;

        private Drive() {
            PrtsChunkDemand.install(candidates);
            PrtsPipelineBatches.install(ledger, () -> TICK);
            PipelineRunSeal.install(candidates::statusFor);
            factory = new PipelineRangeSeals(ledger, table, plans, instances::mintOne,
                world -> EPOCH, () -> declarations, registry);
            PipelineRunSeal.installRange(new PipelineRunSeal.RangeSink() {

                @Override
                public long generation() {
                    return factory.generation();
                }

                @Override
                public FrozenDeclarations.RangeSeal seal(PipelineBatchLedger.TrackedTask tracked,
                                                         DemandShadowCandidates.TaskStatus status,
                                                         long generation) {
                    FrozenDeclarations.RangeSeal read = factory.seal(tracked, status, generation);
                    sealed.set(read);
                    return read;
                }

                @Override
                public void release(FrozenDeclarations.RangeSeal released) {
                    factory.release(released);
                }
            });
            task = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
            ledger.enqueued(task);
            PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, PLAN, TICK);
            assertNotNull(batch);
            List<TickPlan.CommitStep> steps = new ArrayList<>();
            steps.add(new TickPlan.CommitStep(0, WORLD, DOMAIN,
                PipelineRoundJobs.batchKeyOf(batch), 1L, 0, 1, false));
            plans.publish(new TickPlan(TICK, PLAN, 1L, List.of(WORLD), null, List.of(), steps, null,
                List.of(), 0, 0, 0L, null));
        }

        private void run(Runnable body) {
            Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> body;
            Function<ProcessorHandle<Unit>, Runnable> wrapped =
                PipelineBatchWrapper.function(ledger, task, delegate);
            Runnable produced = wrapped.apply(null);
            assertNotNull(produced);
            produced.run();
        }
    }
}
