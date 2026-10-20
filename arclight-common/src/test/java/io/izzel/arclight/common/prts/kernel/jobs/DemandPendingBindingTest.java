/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deferred owner binding: a demand of an early unclaimed task waits for the claimed window of
 * its target cell, and only that window may consume it. */
class DemandPendingBindingTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAIN = "main";
    private static final String OTHER = "worldgen";
    private static final long PLAN = 1L;
    private static final long TICK = 1L;

    @BeforeEach
    void installTheReader() {
        PrtsChunkDemand.install(null);
    }

    @AfterEach
    void removeTheReader() {
        PrtsChunkDemand.installPendingBinder(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    private static PipelineBatchLedger.TrackedTask task(PipelineBatchLedger ledger, String mailbox,
                                                        long cell, long tick) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(null, WORLD, mailbox, cell, tick);
        ledger.enqueued(task);
        ledger.handedOff(task);
        return task;
    }

    private static final class Fixture {

        final PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        final PipelineBatchLedger.TrackedTask caller;
        final PipelineBatchLedger.TrackedTask target;
        final PipelineBatchLedger.BatchId batch;

        Fixture(long cell) {
            caller = task(ledger, OTHER, cell + 1L, TICK);
            assertFalse(ledger.runStart(caller), "the caller runs unclaimed");
            target = task(ledger, MAIN, cell, TICK);
            batch = ledger.freeze(WORLD, MAIN, PLAN, TICK);
            assertNotNull(batch, "the target cell is claimed in main");
            assertTrue(ledger.runStart(target), "the target opens its window");
        }
    }

    private static DemandShadowCandidates candidates() {
        return new DemandShadowCandidates(64, () -> PLAN);
    }

    private static void demand(DemandShadowCandidates candidates, int x, int z, long token,
                               String status, PipelineBatchLedger.TrackedTask caller) {
        candidates.demandDescriptor(WORLD, x, z, status, true, token, caller);
    }

    @Test
    void aDeferredDemandBecomesTheTargetsPresentStatusBeforeItsSeal() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);

        assertEquals(1, candidates.pendingCount(), "the demand waits for its owner");
        assertEquals(1, candidates.bindPending(fixture.target, fixture.ledger));

        DemandShadowCandidates.TaskStatus status = candidates.statusFor(fixture.target);
        assertTrue(status.present(), "the claimed target reads the deferred status");
        assertEquals("biomes", status.status());
        assertEquals(0, candidates.pendingCount(), "one token is consumed once");
        assertEquals(0, candidates.bindPending(fixture.target, fixture.ledger), "a second bind finds nothing");
    }

    @Test
    void aDeferredDemandOfAnotherCellIsNotBound() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        Fixture other = new Fixture(ChunkPos.asLong(2813, 2813));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);

        assertEquals(0, candidates.bindPending(other.target, other.ledger));
        assertFalse(candidates.statusFor(other.target).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void anOldPendingIsNotConsumedByAnotherPlansWindow() {
        long cell = ChunkPos.asLong(2812, 2812);
        Fixture fixture = new Fixture(cell);
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        PipelineBatchLedger.TrackedTask next = task(fixture.ledger, MAIN, cell, TICK + 1L);
        PipelineBatchLedger.BatchId nextBatch = fixture.ledger.freeze(WORLD, MAIN, PLAN + 1L, TICK + 1L);
        assertNotNull(nextBatch);
        assertTrue(fixture.ledger.runStart(next));

        assertEquals(0, candidates.bindPending(next, fixture.ledger));
        assertFalse(candidates.statusFor(next).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void aWrongExpectedMailboxDoesNotBind() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);

        assertEquals(0, candidates.bindPending(fixture.target, fixture.ledger, OTHER));
        assertFalse(candidates.statusFor(fixture.target).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void anUnpublishedBatchDoesNotBind() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        PipelineBatchLedger other = new PipelineBatchLedger(64, 1000);

        assertEquals(0, candidates.bindPending(fixture.target, other));
        assertFalse(candidates.statusFor(fixture.target).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void aStalePlanDoesNotBind() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        fixture.ledger.freeze(WORLD, MAIN, PLAN + 1L, TICK + 1L);

        assertEquals(0, candidates.bindPending(fixture.target, fixture.ledger));
        assertFalse(candidates.statusFor(fixture.target).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void aWindowThatDoesNotOwnTheCellDoesNotBind() {
        long cell = ChunkPos.asLong(2812, 2812);
        PipelineBatchLedger ledger = new PipelineBatchLedger(64, 1000);
        PipelineBatchLedger.TrackedTask caller = task(ledger, OTHER, cell + 1L, TICK);
        assertFalse(ledger.runStart(caller));
        PipelineBatchLedger.TrackedTask owner = task(ledger, MAIN, cell, TICK);
        PipelineBatchLedger.TrackedTask other = task(ledger, MAIN, cell, TICK);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAIN, PLAN, TICK);
        assertNotNull(batch);
        assertTrue(ledger.runStart(owner));
        assertTrue(ledger.runStart(other));
        assertEquals(owner.id().ordinal(), ledger.ownerOrdinal(batch, cell));

        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", caller);
        assertEquals(0, candidates.bindPending(other, ledger));
        assertFalse(candidates.statusFor(other).present());
        assertEquals(1, candidates.bindPending(owner, ledger));
        assertTrue(candidates.statusFor(owner).present());
    }

    @Test
    void expiredDemandsAreDroppedAndDoNotBind() {
        AtomicLong tick = new AtomicLong(0L);
        PrtsPipelineBatches.install(null, tick::get);
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        tick.set(DemandShadowCandidates.PENDING_TTL_TICKS + 1L);

        assertEquals(0, candidates.bindPending(fixture.target, fixture.ledger));
        assertFalse(candidates.statusFor(fixture.target).present());
        assertEquals(0, candidates.pendingCount(), "an expired demand is not kept");
    }

    @Test
    void thePendingTableIsBounded() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = new DemandShadowCandidates(8);
        for (int token = 1; token <= 3; token++) {
            demand(candidates, 2812, 2812, token, "biomes", fixture.caller);
        }
        assertEquals(1, candidates.pendingCount(), "the pending capacity is a fraction of the register");
    }

    @Test
    void oneTokenIsConsumedOnceEvenWhenTheDemandRepeats() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        assertEquals(1, candidates.pendingCount());
        assertEquals(1, candidates.bindPending(fixture.target, fixture.ledger));
    }

    @Test
    void sameStatusDuplicatesAndDifferentStatusConflicts() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates duplicate = candidates();
        demand(duplicate, 2812, 2812, 19L, "biomes", fixture.caller);
        demand(duplicate, 2812, 2812, 20L, "biomes", fixture.caller);
        assertEquals(2, duplicate.bindPending(fixture.target, fixture.ledger));
        assertTrue(duplicate.statusFor(fixture.target).present(), "the same status is a duplicate");

        Fixture conflictFixture = new Fixture(ChunkPos.asLong(2813, 2813));
        DemandShadowCandidates conflict = candidates();
        demand(conflict, 2813, 2813, 30L, "biomes", conflictFixture.caller);
        demand(conflict, 2813, 2813, 31L, "full", conflictFixture.caller);
        assertEquals(2, conflict.bindPending(conflictFixture.target, conflictFixture.ledger));
        assertFalse(conflict.statusFor(conflictFixture.target).present(), "a conflict has no status");
    }

    @Test
    void resetDropsWaitingDemands() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        candidates.resetStatuses();
        assertEquals(0, candidates.pendingCount());
        assertEquals(0, candidates.bindPending(fixture.target, fixture.ledger));
    }

    @Test
    void noInstalledBinderLeavesTheDemandWaiting() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        PrtsChunkDemand.installPendingBinder(null);

        PrtsChunkDemand.bindPending(fixture.target);

        assertFalse(candidates.statusFor(fixture.target).present());
        assertEquals(1, candidates.pendingCount());
    }

    @Test
    void theInstalledBinderIsTheOnlyBridge() {
        Fixture fixture = new Fixture(ChunkPos.asLong(2812, 2812));
        DemandShadowCandidates candidates = candidates();
        demand(candidates, 2812, 2812, 19L, "biomes", fixture.caller);
        PrtsChunkDemand.installPendingBinder(task -> candidates.bindPending(
            (PipelineBatchLedger.TrackedTask) task, fixture.ledger));
        PrtsChunkDemand.bindPending(fixture.target);
        assertTrue(candidates.statusFor(fixture.target).present());
        assertEquals(0, candidates.pendingCount());
    }
}
