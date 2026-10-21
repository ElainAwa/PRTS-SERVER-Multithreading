/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real wrapper path: the kernel installs the binder, the demand tap enters through the support
 * seam, and the claimed window binds the waiting demand before its one status read. */
class DemandPendingEndToEndTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAIN = "main";
    private static final String OTHER = "worldgen";
    private static final long PLAN = 1L;
    private static final long TICK = 1L;

    private KernelModule kernel;

    @BeforeEach
    void installTheRealProbe() {
        System.setProperty("arclight.prts.kernel", "true");
        kernel = KernelModule.instance();
        kernel.removeLoadProbe();
        kernel.resetReadings();
        kernel.installLoadProbe();
        kernel.installPipelineRowTap();
    }

    @AfterEach
    void removeTheRealProbe() {
        kernel.removePipelineRowTap();
        kernel.removeLoadProbe();
        kernel.resetReadings();
        System.clearProperty("arclight.prts.kernel");
    }

    private static PipelineBatchLedger.TrackedTask task(PipelineBatchLedger ledger, String mailbox,
                                                        long cell, long tick) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, mailbox, cell, tick);
        ledger.enqueued(task);
        ledger.handedOff(task);
        return task;
    }

    @Test
    void theWrapperBindsTheDeferredDemandBeforeTheSeal() {
        PipelineBatchLedger ledger = kernel.pipelineBatches();
        DemandShadowCandidates shadows = kernel.demandShadowCandidates();
        long cell = ChunkPos.asLong(2812, 2812);
        PipelineBatchLedger.TrackedTask caller = task(ledger, OTHER, cell + 1L, TICK);
        assertFalse(ledger.runStart(caller), "the caller is early unclaimed");
        PipelineBatchLedger.TrackedTask target = task(ledger, MAIN, cell, TICK);
        assertNotNull(ledger.freeze(WORLD, MAIN, PLAN, TICK));
        long token = PrtsChunkDemand.demandDescriptorToken(WORLD, 2812, 2812, "biomes", true, caller);
        assertTrue(token > PrtsChunkDemand.NO_TOKEN);
        assertEquals(1, shadows.pendingCount());

        Runnable body = PipelineBatchWrapper.function(ledger, target, reply -> (Runnable) () -> {})
            .apply(null);
        body.run();

        List<DemandShadowCandidates.TaskStatus> retained = shadows.retainedStatuses();
        assertFalse(retained.isEmpty(), "the target window retained the bound status");
        assertTrue(retained.stream().anyMatch(status -> status.present()
            && "biomes".equals(status.status())));
        assertEquals(0, shadows.pendingCount(), "the wrapper path consumed the pending demand");
    }

    @Test
    void removingTheLoadProbeClearsPendingDemands() {
        PipelineBatchLedger ledger = kernel.pipelineBatches();
        DemandShadowCandidates shadows = kernel.demandShadowCandidates();
        long cell = ChunkPos.asLong(2814, 2814);
        PipelineBatchLedger.TrackedTask caller = task(ledger, OTHER, cell + 1L, TICK);
        assertFalse(ledger.runStart(caller));
        assertTrue(PrtsChunkDemand.demandDescriptorToken(WORLD, 2814, 2814, "biomes", true, caller)
            > PrtsChunkDemand.NO_TOKEN);
        assertEquals(1, shadows.pendingCount());

        kernel.removeLoadProbe();

        assertEquals(0, shadows.pendingCount(), "uninstall clears the deferred register");
    }

    @Test
    void oneWindowTakesOneStatusOfTwoPendingDemands() {
        PipelineBatchLedger ledger = kernel.pipelineBatches();
        DemandShadowCandidates shadows = kernel.demandShadowCandidates();
        long cell = ChunkPos.asLong(2816, 2816);
        PipelineBatchLedger.TrackedTask caller = task(ledger, OTHER, cell + 1L, TICK);
        assertFalse(ledger.runStart(caller));
        PipelineBatchLedger.TrackedTask target = task(ledger, MAIN, cell, TICK);
        assertNotNull(ledger.freeze(WORLD, MAIN, PLAN, TICK));
        assertTrue(PrtsChunkDemand.demandDescriptorToken(WORLD, 2816, 2816, "biomes", true, caller)
            > PrtsChunkDemand.NO_TOKEN);
        assertTrue(PrtsChunkDemand.demandDescriptorToken(WORLD, 2816, 2816, "full", true, caller)
            > PrtsChunkDemand.NO_TOKEN);
        assertEquals(2, shadows.pendingCount());

        Runnable body = PipelineBatchWrapper.function(ledger, target, reply -> (Runnable) () -> {})
            .apply(null);
        body.run();

        List<DemandShadowCandidates.TaskStatus> retained = shadows.retainedStatuses();
        assertTrue(retained.stream().anyMatch(status -> status.present()
            && "full".equals(status.status())));
        assertEquals(1, shadows.pendingCount(), "the older status waits for a later window");
    }

    @Test
    void anEarlyPlanZeroDemandBindsWhenTheFirstPlanWindowOpens() {
        PipelineBatchLedger ledger = kernel.pipelineBatches();
        DemandShadowCandidates shadows = kernel.demandShadowCandidates();
        long cell = ChunkPos.asLong(2815, 2815);
        PipelineBatchLedger.TrackedTask caller = task(ledger, OTHER, cell + 1L, TICK);
        assertFalse(ledger.runStart(caller), "the caller is early unclaimed before any freeze");
        long token = PrtsChunkDemand.demandDescriptorToken(WORLD, 2815, 2815, "biomes", true, caller);
        assertTrue(token > PrtsChunkDemand.NO_TOKEN);
        assertEquals(1, shadows.pendingCount(), "the early demand records plan zero");

        PipelineBatchLedger.TrackedTask target = task(ledger, MAIN, cell, TICK);
        assertNotNull(ledger.freeze(WORLD, MAIN, PLAN, TICK));
        Runnable body = PipelineBatchWrapper.function(ledger, target, reply -> (Runnable) () -> {})
            .apply(null);
        body.run();

        List<DemandShadowCandidates.TaskStatus> retained = shadows.retainedStatuses();
        assertFalse(retained.isEmpty(), "the first claimed window retains the early demand");
        assertTrue(retained.stream().anyMatch(status -> status.present()
            && "biomes".equals(status.status())));
        assertEquals(0, shadows.pendingCount());
    }

    @Test
    void theWrapperWithoutAPendingDemandRetainsNothing() {
        PipelineBatchLedger ledger = kernel.pipelineBatches();
        DemandShadowCandidates shadows = kernel.demandShadowCandidates();
        long cell = ChunkPos.asLong(2813, 2813);
        PipelineBatchLedger.TrackedTask target = task(ledger, MAIN, cell, TICK);
        assertNotNull(ledger.freeze(WORLD, MAIN, PLAN, TICK));

        Runnable body = PipelineBatchWrapper.function(ledger, target, reply -> (Runnable) () -> {})
            .apply(null);
        body.run();

        assertTrue(shadows.retainedStatuses().isEmpty());
        assertEquals(0, shadows.pendingCount());
    }
}
