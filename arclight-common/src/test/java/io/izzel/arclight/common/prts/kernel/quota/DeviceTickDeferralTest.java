/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded DEVICE execution boundary: an under-budget plan runs every tick in place, an
 * over-budget plan defers the remaining ticks to the tick's safe point in their original order and
 * each of them runs exactly once, the queue/receipts are bounded, a disabled/empty/foreign plan
 * acts nothing, and a world whose lifecycle epoch moved drops its deferred ticks without reporting
 * an executed action. */
class DeviceTickDeferralTest {

    private static final String WORLD = "device-world";
    private static final Object SCOPE = new Object();
    private static final long TICK = 41L;
    private static final long EPOCH = 9L;

    @BeforeEach
    void reset() {
        DeviceTickDeferral.reset();
        FakeTicker.log.clear();
    }

    @Test
    void anUnderBudgetPlanRunsEveryTickInPlace() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1_000L, true));
        FakeTicker first = new FakeTicker("a");
        FakeTicker second = new FakeTicker("b");
        assertFalse(DeviceTickDeferral.defer(SCOPE, first));
        DeviceTickDeferral.noteInline(SCOPE, 200L);
        assertFalse(DeviceTickDeferral.defer(SCOPE, second));
        DeviceTickDeferral.noteInline(SCOPE, 200L);
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);

        assertEquals(0L, DeviceTickDeferral.overCount(), "nothing was deferred");
        assertTrue(DeviceTickDeferral.receipts().isEmpty(), "no action receipt for a clean plan");
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)));
        assertTrue(first.ticks == 0 && second.ticks == 0, "the phase ran them, the boundary did not");
    }

    @Test
    void anOverBudgetPlanDefersTheRestInOrderAndTheSafePointRunsEachOnce() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 250L, true));
        FakeTicker first = new FakeTicker("a");
        FakeTicker second = new FakeTicker("b");
        FakeTicker third = new FakeTicker("c");
        assertFalse(DeviceTickDeferral.defer(SCOPE, first));
        DeviceTickDeferral.noteInline(SCOPE, 300L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, second));
        assertTrue(DeviceTickDeferral.defer(SCOPE, third));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);

        assertEquals(1L, DeviceTickDeferral.overCount());
        assertEquals(2, DeviceTickDeferral.drain(epochs(EPOCH)),
            "the safe point drains the two deferred ticks");
        assertEquals(List.of("b", "c"), FakeTicker.log, "original order is kept");
        assertEquals(1, second.ticks);
        assertEquals(1, third.ticks);
        assertEquals(2, second.ticks + third.ticks, "each deferred tick runs exactly once");
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)), "a replay of the safe point drains nothing");

        List<DeviceTickDeferral.Receipt> receipts = DeviceTickDeferral.receipts();
        assertEquals(1, receipts.size(), "one receipt per over plan");
        DeviceTickDeferral.Receipt receipt = receipts.get(0);
        assertEquals(WORLD, receipt.worldId());
        assertEquals(TICK, receipt.tickIndex());
        assertEquals(EPOCH, receipt.worldEpoch());
        assertEquals(250L, receipt.budgetNanos());
        assertEquals(300L, receipt.usedNanos());
        assertEquals(1, receipt.inlineTicks());
        assertEquals(2, receipt.deferredTicks());
        assertEquals(2, receipt.drainedTicks());
        assertEquals(0, receipt.droppedTicks());
        assertEquals(DeviceTickDeferral.Result.DEFERRED, receipt.result());
    }

    @Test
    void theLevelTickDrainsItsOwnWaitingPlanAtItsOwnReturn() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 10L, true));
        FakeTicker ticker = new FakeTicker("same-tick");
        DeviceTickDeferral.noteInline(SCOPE, 11L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, ticker));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertTrue(DeviceTickDeferral.hasWaiting(SCOPE),
            "the plan waits for the RETURN of the very tick that deferred it");

        assertEquals(1, DeviceTickDeferral.drainScope(SCOPE, epochs(EPOCH)),
            "the tick drains its own waiting plan at its own RETURN");
        assertEquals(1, ticker.ticks);
        assertFalse(DeviceTickDeferral.hasWaiting(SCOPE));
        assertEquals(DeviceTickDeferral.Result.DEFERRED,
            DeviceTickDeferral.receipts().get(0).result());
        assertEquals(0, DeviceTickDeferral.drainScope(SCOPE, epochs(EPOCH)),
            "a second RETURN of the same tick finds nothing");
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)),
            "the module fallback finds nothing after the level drained");
    }

    @Test
    void aScopedDrainLeavesAnotherLevelsPlanWaiting() {
        Object other = new Object();
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true));
        FakeTicker first = new FakeTicker("first");
        DeviceTickDeferral.noteInline(SCOPE, 2L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, first));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertTrue(DeviceTickDeferral.begin(other, "other-world", TICK, EPOCH, 1L, true));
        FakeTicker second = new FakeTicker("second");
        DeviceTickDeferral.noteInline(other, 2L);
        assertTrue(DeviceTickDeferral.defer(other, second));
        DeviceTickDeferral.end(other, TICK, EPOCH);

        assertEquals(1, DeviceTickDeferral.drainScope(other, epochs(EPOCH)));
        assertEquals(1, second.ticks);
        assertEquals(0, first.ticks, "another level's deferred tick is not drained");
        assertTrue(DeviceTickDeferral.hasWaiting(SCOPE));
        assertFalse(DeviceTickDeferral.hasWaiting(other));

        assertEquals(1, DeviceTickDeferral.drain(epochs(EPOCH)),
            "the module fallback drains only the plan that was left waiting");
        assertEquals(1, first.ticks);
        assertEquals(0, DeviceTickDeferral.drainScope(new Object(), epochs(EPOCH)));
    }

    @Test
    void theDeferredQueueIsBoundedAndTheExcessRunsInPlace() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true));
        FakeTicker inline = new FakeTicker("inline");
        assertFalse(DeviceTickDeferral.defer(SCOPE, inline));
        DeviceTickDeferral.noteInline(SCOPE, 2L);
        List<FakeTicker> deferredOnes = new ArrayList<>();
        int forced = 0;
        for (int index = 0; index < DeviceTickDeferral.DEFERRED_LIMIT + 36; index++) {
            FakeTicker ticker = new FakeTicker("t" + index);
            deferredOnes.add(ticker);
            if (!DeviceTickDeferral.defer(SCOPE, ticker)) {
                // The tick path runs it in place when the bounded queue is full.
                ticker.tick();
                forced++;
            }
        }
        assertEquals(36, forced, "only the excess above the bound runs in place");
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(DeviceTickDeferral.DEFERRED_LIMIT, DeviceTickDeferral.drain(epochs(EPOCH)),
            "the safe point runs the bounded queue");
        assertEquals(1L, DeviceTickDeferral.overCount());
        assertEquals(36L, DeviceTickDeferral.forcedCount());
        int ticked = 0;
        for (FakeTicker ticker : deferredOnes) {
            ticked += ticker.ticks;
        }
        assertEquals(forced + DeviceTickDeferral.DEFERRED_LIMIT, ticked,
            "no tick is lost above the bound either");
    }

    @Test
    void aDisabledPlanActsNothing() {
        assertFalse(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1_000L, false));
        FakeTicker ticker = new FakeTicker("a");
        assertFalse(DeviceTickDeferral.defer(SCOPE, ticker));
        DeviceTickDeferral.noteInline(SCOPE, 5_000L);
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(0L, DeviceTickDeferral.overCount());
        assertTrue(DeviceTickDeferral.receipts().isEmpty());
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)));
    }

    @Test
    void anEmptyOrForeignScopeActsNothing() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1_000L, true));
        FakeTicker ticker = new FakeTicker("a");
        assertFalse(DeviceTickDeferral.defer(new Object(), ticker), "another scope has no plan");
        assertFalse(DeviceTickDeferral.defer(null, ticker));
        assertFalse(DeviceTickDeferral.defer(SCOPE, null));
        DeviceTickDeferral.noteInline(new Object(), 5_000L);
        DeviceTickDeferral.end(new Object(), TICK, EPOCH);
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(0L, DeviceTickDeferral.overCount(), "no tick was deferred");
        assertTrue(DeviceTickDeferral.receipts().isEmpty());
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)));
        assertEquals(0, ticker.ticks);
    }

    @Test
    void anEmptyPlanRecordsNoAction() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1_000L, true));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(0L, DeviceTickDeferral.overCount());
        assertTrue(DeviceTickDeferral.receipts().isEmpty());
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)));
    }

    @Test
    void aMovedLifecycleEpochDropsTheDeferredTicksWithoutAnAction() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true));
        FakeTicker first = new FakeTicker("a");
        FakeTicker second = new FakeTicker("b");
        DeviceTickDeferral.noteInline(SCOPE, 5L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, first));
        assertTrue(DeviceTickDeferral.defer(SCOPE, second));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);

        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH + 1L)),
            "the world was replaced before the safe point");
        assertEquals(0, first.ticks);
        assertEquals(0, second.ticks);
        assertEquals(1, DeviceTickDeferral.receipts().size());
        DeviceTickDeferral.Receipt receipt = DeviceTickDeferral.receipts().get(0);
        assertEquals(DeviceTickDeferral.Result.INVALIDATED, receipt.result());
        assertEquals(0, receipt.drainedTicks());
        assertEquals(2, receipt.droppedTicks());
        assertEquals(2L, DeviceTickDeferral.droppedCount());
    }

    @Test
    void anUntrackedLifecycleHasNoIdentity() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true));
        FakeTicker ticker = new FakeTicker("a");
        DeviceTickDeferral.noteInline(SCOPE, 5L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, ticker));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(0, DeviceTickDeferral.drain(null), "no epoch source means no proven world");
        assertEquals(0, ticker.ticks);
        assertEquals(DeviceTickDeferral.Result.INVALIDATED,
            DeviceTickDeferral.receipts().get(0).result());
    }

    @Test
    void repeatedBeginAndResetKeepTheBoundaryIdempotent() {
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true));
        assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, TICK, EPOCH, 1L, true),
            "the same identity keeps the plan");
        FakeTicker ticker = new FakeTicker("a");
        DeviceTickDeferral.noteInline(SCOPE, 5L);
        assertTrue(DeviceTickDeferral.defer(SCOPE, ticker));
        DeviceTickDeferral.end(SCOPE, TICK, EPOCH);
        assertEquals(1L, DeviceTickDeferral.overCount());

        DeviceTickDeferral.reset();
        assertEquals(0L, DeviceTickDeferral.overCount());
        assertTrue(DeviceTickDeferral.receipts().isEmpty());
        assertEquals(0, DeviceTickDeferral.drain(epochs(EPOCH)), "a reset drops the waiting plan");
        assertFalse(DeviceTickDeferral.defer(SCOPE, ticker), "a reset plan is not reopened");
        assertEquals(0, ticker.ticks);
    }

    @Test
    void theReceiptLogIsBounded() {
        for (int index = 0; index < DeviceTickDeferral.RECEIPT_LIMIT + 10; index++) {
            long tick = TICK + index;
            assertTrue(DeviceTickDeferral.begin(SCOPE, WORLD, tick, EPOCH, 1L, true));
            DeviceTickDeferral.noteInline(SCOPE, 2L);
            assertTrue(DeviceTickDeferral.defer(SCOPE, new FakeTicker("d" + index)));
            DeviceTickDeferral.end(SCOPE, tick, EPOCH);
            DeviceTickDeferral.drain(epochs(EPOCH));
        }
        assertEquals(DeviceTickDeferral.RECEIPT_LIMIT, DeviceTickDeferral.receipts().size());
    }

    private static ToLongFunction<String> epochs(long epoch) {
        return worldId -> epoch;
    }

    private static final class FakeTicker implements TickingBlockEntity {

        private static final List<String> log = new ArrayList<>();

        private final String name;
        private int ticks;
        private boolean removed;

        private FakeTicker(String name) {
            this.name = name;
        }

        @Override
        public void tick() {
            ticks++;
            log.add(name);
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos getPos() {
            return BlockPos.ZERO;
        }

        @Override
        public String getType() {
            return name;
        }
    }
}
