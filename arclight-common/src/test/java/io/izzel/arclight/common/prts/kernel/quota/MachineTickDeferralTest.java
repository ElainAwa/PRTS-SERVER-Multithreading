/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.quota;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded MACHINE action: while armed, root neighbour updates are replayed once each, in
 * original order, at the tick's end safe point; an unarmed tick and a moved lifecycle act nothing;
 * the bound flushes in place without losing or reordering a call; replay can never re-enter the
 * queue; receipts and the queue are bounded. */
class MachineTickDeferralTest {

    private static final String WORLD = "machine-world";
    private static final long TICK = 71L;
    private static final long EPOCH = 5L;
    private static final Object MARK = new Object();

    private final List<Integer> replayed = new ArrayList<>();
    private final List<Integer> inline = new ArrayList<>();
    private final Object level = new Object();
    private final MachineTickDeferral.Replay replay =
        (state, pos, block, facing) -> replayed.add(pos.getX());

    @BeforeEach
    void reset() {
        MachineTickDeferral.reset();
        replayed.clear();
        inline.clear();
    }

    @Test
    void anArmedTickReplaysEveryCallOnceInOrderAtTheSafePoint() {
        assertTrue(defer(level, 0));
        assertTrue(defer(level, 1));
        assertTrue(defer(level, 2));
        assertEquals(0L, MachineTickDeferral.drainedCount(), "nothing runs before the safe point");

        assertEquals(3, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertEquals(List.of(0, 1, 2), replayed, "original order is kept at the safe point");
        assertEquals(0, MachineTickDeferral.drain(epochs(EPOCH), replay),
            "a replay of the safe point drains nothing");

        List<MachineTickDeferral.Receipt> receipts = MachineTickDeferral.receipts();
        assertEquals(1, receipts.size(), "one receipt per armed tick with an action");
        MachineTickDeferral.Receipt receipt = receipts.get(0);
        assertEquals(WORLD, receipt.worldId());
        assertEquals(TICK, receipt.tickIndex());
        assertEquals(EPOCH, receipt.worldEpoch());
        assertEquals(3, receipt.deferredTicks());
        assertEquals(0, receipt.flushedTicks());
        assertEquals(3, receipt.drainedTicks());
        assertEquals(0, receipt.droppedTicks());
        assertEquals(MachineTickDeferral.Result.DEFERRED, receipt.result());
    }

    @Test
    void anUnarmedTickRunsInlineAndKeepsNoState() {
        assertFalse(defer(level, 0, false));
        assertFalse(MachineTickDeferral.hasWaiting());
        assertEquals(0, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertTrue(replayed.isEmpty());
        assertTrue(MachineTickDeferral.receipts().isEmpty());
    }

    @Test
    void theBoundFlushesInOrderWithoutLosingOrReorderingACall() {
        for (int index = 0; index < MachineTickDeferral.DEFERRED_LIMIT; index++) {
            assertTrue(defer(level, index));
        }
        int overflow = MachineTickDeferral.DEFERRED_LIMIT;
        assertTrue(defer(level, overflow),
            "at the bound the queue is flushed in place and the new call still waits");
        for (int index = overflow + 1; index < overflow + 6; index++) {
            assertTrue(defer(level, index));
        }
        assertEquals(MachineTickDeferral.DEFERRED_LIMIT, replayed.size(),
            "the bound flushes the queued calls in place");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), replayed.subList(0, 10),
            "the flush keeps the original order");
        assertEquals(6, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertEquals(List.of(overflow, overflow + 1, overflow + 2, overflow + 3, overflow + 4,
            overflow + 5), replayed.subList(MachineTickDeferral.DEFERRED_LIMIT, replayed.size()),
            "the calls after the flush keep the original order too");
        assertTrue(inline.isEmpty(), "no call ran out of order in place");

        MachineTickDeferral.Receipt receipt = MachineTickDeferral.receipts().get(0);
        assertEquals(MachineTickDeferral.DEFERRED_LIMIT + 6, receipt.deferredTicks());
        assertEquals(MachineTickDeferral.DEFERRED_LIMIT, receipt.flushedTicks());
        assertEquals(6, receipt.drainedTicks());
        assertEquals(0, receipt.droppedTicks());
        assertEquals(MachineTickDeferral.Result.DEFERRED, receipt.result());
    }

    @Test
    void aReplayCannotReenterTheQueue() {
        List<Boolean> reentered = new ArrayList<>();
        MachineTickDeferral.Replay checkingReplay = (state, pos, block, facing) -> {
            reentered.add(MachineTickDeferral.defer(level, WORLD, TICK, EPOCH, pos, block, facing,
                true, (innerState, innerPos, innerBlock, innerFacing) -> { }));
            replayed.add(pos.getX());
        };
        assertTrue(defer(level, 0));
        assertEquals(1, MachineTickDeferral.drain(epochs(EPOCH), checkingReplay));
        assertEquals(List.of(false), reentered, "a replay never enters the queue again");
        assertEquals(1L, MachineTickDeferral.deferredCount(), "the replay added no call");
    }

    @Test
    void aMovedLifecycleAtTheSafePointInvalidatesWithoutFakingAnExecution() {
        assertTrue(defer(level, 0));
        assertTrue(defer(level, 1));
        assertEquals(0, MachineTickDeferral.drain(epochs(EPOCH + 1L), replay),
            "the world lifecycle moved before the safe point");
        assertTrue(replayed.isEmpty(), "no call is replayed into another world generation");
        MachineTickDeferral.Receipt receipt = MachineTickDeferral.receipts().get(0);
        assertEquals(MachineTickDeferral.Result.INVALIDATED, receipt.result());
        assertEquals(0, receipt.drainedTicks());
        assertEquals(2, receipt.droppedTicks());
    }

    @Test
    void aLevelReturnDrainsItsOwnPlanBeforeTheNextTick() {
        assertTrue(defer(level, 0));
        assertTrue(MachineTickDeferral.hasWaiting(level),
            "the plan waits for this level tick's own RETURN");
        assertEquals(1, MachineTickDeferral.drainScope(level, epochs(EPOCH), replay));
        assertFalse(MachineTickDeferral.hasWaiting(level));
        assertFalse(MachineTickDeferral.hasWaiting(), "nothing is carried into the next tick");
        assertTrue(defer(level, TICK + 1L, EPOCH, 1), "the next tick opens its own plan");
        assertEquals(1, MachineTickDeferral.drainScope(level, epochs(EPOCH), replay));
        assertEquals(List.of(0, 1), replayed);
        assertEquals(2, MachineTickDeferral.receipts().size());
        assertEquals(TICK, MachineTickDeferral.receipts().get(0).tickIndex());
        assertEquals(TICK + 1L, MachineTickDeferral.receipts().get(1).tickIndex());
    }

    @Test
    void aMissedSafePointReplaysTheOldPlanLateInsteadOfSilentlyDroppingIt() {
        assertTrue(defer(level, 0));
        assertTrue(defer(level, TICK + 1L, EPOCH, 1), "the next tick opens its own plan");
        assertEquals(List.of(0), replayed, "the missed plan is replayed late, in order");
        MachineTickDeferral.Receipt missed = MachineTickDeferral.receipts().get(0);
        assertEquals(TICK, missed.tickIndex());
        assertEquals(MachineTickDeferral.Result.DEFERRED, missed.result(),
            "a same-lifecycle missed safe point is an explicit late replay");
        assertEquals(1, missed.drainedTicks());
        assertEquals(0, missed.droppedTicks());
        assertEquals(1, MachineTickDeferral.drainScope(level, epochs(EPOCH), replay));
        assertEquals(List.of(0, 1), replayed);
    }

    @Test
    void aForeignLifecycleIsExplicitlyInvalidatedNotSilentlyDropped() {
        assertTrue(defer(level, 0));
        assertTrue(MachineTickDeferral.defer(level, WORLD, TICK + 1L, EPOCH + 1L,
            new BlockPos(1, 0, 0), MARK, null, true, replay),
            "the next generation opens its own plan");
        MachineTickDeferral.Receipt retired = MachineTickDeferral.receipts().get(0);
        assertEquals(MachineTickDeferral.Result.INVALIDATED, retired.result());
        assertEquals(TICK, retired.tickIndex());
        assertEquals(EPOCH, retired.worldEpoch());
        assertEquals(1, retired.droppedTicks(), "the dropped count is explicit on the receipt");
        assertTrue(replayed.isEmpty(), "a foreign lifecycle is not replayed into the new generation");
        assertEquals(1, MachineTickDeferral.drainScope(level, epochs(EPOCH + 1L), replay));
        assertEquals(List.of(1), replayed);
    }

    @Test
    void theModuleFallbackOnlyDrainsWhatTheLevelReturnMissed() {
        assertTrue(defer(level, 0));
        assertEquals(1, MachineTickDeferral.drainScope(level, epochs(EPOCH), replay));
        Object lateLevel = new Object();
        assertTrue(MachineTickDeferral.defer(lateLevel, WORLD, TICK, EPOCH,
            new BlockPos(5, 0, 0), MARK, null, true, replay));
        assertEquals(0, MachineTickDeferral.drainScope(level, epochs(EPOCH), replay),
            "the drained level has nothing left");
        assertEquals(1, MachineTickDeferral.drain(epochs(EPOCH), replay),
            "the module fallback drains the call the level RETURN missed");
        assertEquals(List.of(0, 5), replayed);
    }

    @Test
    void eachLevelDrainsItsOwnCallsInTheOriginalOrder() {
        Object otherLevel = new Object();
        assertTrue(defer(level, 0));
        assertTrue(MachineTickDeferral.defer(otherLevel, WORLD, TICK, EPOCH,
            new BlockPos(9, 0, 0), MARK, null, true, replay));
        assertEquals(2, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertEquals(List.of(0, 9), replayed);
    }

    @Test
    void resetClearsTheQueueAndTheReceipts() {
        assertTrue(defer(level, 0));
        MachineTickDeferral.reset();
        assertFalse(MachineTickDeferral.hasWaiting());
        assertTrue(MachineTickDeferral.receipts().isEmpty());
        assertEquals(0L, MachineTickDeferral.deferredCount());
        assertEquals(0, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertTrue(replayed.isEmpty());
    }

    @Test
    void theReceiptLogIsBounded() {
        for (int index = 0; index < MachineTickDeferral.RECEIPT_LIMIT + 10; index++) {
            assertTrue(MachineTickDeferral.defer(level, WORLD, TICK + index, EPOCH,
                new BlockPos(0, 0, 0), MARK, null, true, replay));
            MachineTickDeferral.drain(epochs(EPOCH), replay);
        }
        assertEquals(MachineTickDeferral.RECEIPT_LIMIT, MachineTickDeferral.receipts().size());
    }

    @Test
    void forgetWorldDropsOnlyThatWorldAtUnload() {
        assertTrue(defer(level, 0));
        assertTrue(MachineTickDeferral.hasWaiting(level));

        assertEquals(1, MachineTickDeferral.forgetWorld(WORLD));
        assertFalse(MachineTickDeferral.hasWaiting(level));
        assertEquals(0, MachineTickDeferral.drain(epochs(EPOCH), replay));
        assertTrue(replayed.isEmpty(), "an unloaded level cannot replay its deferred calls");
        assertEquals(1L, MachineTickDeferral.droppedCount());
    }

    private boolean defer(Object scope, int index) {
        return defer(scope, index, true);
    }

    private boolean defer(Object scope, long tickIndex, long epoch, int index) {
        return MachineTickDeferral.defer(scope, WORLD, tickIndex, epoch,
            new BlockPos(index, 0, 0), MARK, (Direction) null, true, replay);
    }

    private boolean defer(Object scope, int index, boolean armed) {
        return MachineTickDeferral.defer(scope, WORLD, TICK, EPOCH,
            new BlockPos(index, 0, 0), MARK, (Direction) null, armed, replay);
    }

    private static ToLongFunction<String> epochs(long epoch) {
        return worldId -> epoch;
    }
}
