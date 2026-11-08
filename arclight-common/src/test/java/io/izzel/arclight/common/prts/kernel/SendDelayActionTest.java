/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded decision behind the send delay: it reads a real B01-A budget, fails open on stale
 * or unreadable input, and never retains chunk identities. */
class SendDelayActionTest {

    private static final String WORLD = "minecraft:overworld";
    private final AtomicLong tick = new AtomicLong(10L);
    private final Map<String, Long> epochs = new HashMap<>();
    private final AtomicReference<List<WorldResChildBudget>> budgets = new AtomicReference<>(List.of());

    SendDelayActionTest() {
        epochs.put(WORLD, 1L);
        epochs.put("minecraft:the_end", 1L);
    }

    @AfterEach
    void cleanUp() {
        SendDelayAction.install(false, null, null, null);
    }

    private void arm() {
        SendDelayAction.install(true, tick::get,
            world -> epochs.getOrDefault(world, 0L), budgets::get);
    }

    private static WorldResChildBudget budget(String world, long tick, long epoch, boolean over) {
        ShareTable table = new SharePlanner().plan(List.of(world), tick, Map.of());
        double parent = table.row(world, ShareClass.WORLDRES).shareMs();
        return WorldResChildBudget.settle(table, world, epoch,
            WorldResChildBudget.ChildDemand.ofTaskNanos(1_000_000L, 500_000L),
            over ? parent * 2.0 : 0.0, 0.0);
    }

    @Test
    void disabledOrNoPendingNeverDefers() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        SendDelayAction.install(false, null, null, null);
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 0));
    }

    @Test
    void aFreshParentOverDefersThenRecoveryAllowsTheSend() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        tick.set(11L);
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        tick.set(12L);
        budgets.set(List.of(budget(WORLD, 12L, 1L, false)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
    }

    @Test
    void aStaleBudgetOrMissingEpochFallsOpen() {
        arm();
        budgets.set(List.of(budget(WORLD, 5L, 1L, true)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
        tick.set(13L);
        budgets.set(List.of(budget(WORLD, 13L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        epochs.put(WORLD, 2L);
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
        epochs.remove(WORLD);
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
    }

    @Test
    void theDelayIsCappedByTicksAndWorldsAndHostPendingSize() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        tick.set(13L);
        budgets.set(List.of(budget(WORLD, 13L, 1L, true)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4), "the max delay ticks expired");
        assertFalse(SendDelayAction.shouldDefer(WORLD, SendDelayAction.MAX_PENDING + 1));

        tick.set(10L);
        List<WorldResChildBudget> many = new ArrayList<>();
        for (int index = 0; index < SendDelayAction.MAX_WORLDS + 1; index++) {
            String world = "world-" + index;
            epochs.put(world, 1L);
            many.add(budget(world, 10L, 1L, true));
        }
        budgets.set(many);
        for (int index = 0; index < SendDelayAction.MAX_WORLDS; index++) {
            assertTrue(SendDelayAction.shouldDefer("world-" + index, 1));
        }
        assertFalse(SendDelayAction.shouldDefer("world-" + SendDelayAction.MAX_WORLDS, 1));
    }

    @Test
    void delayedPassCountTracksActualDeferralsAndClearsPerGeneration() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        tick.set(11L);
        budgets.set(List.of(budget(WORLD, 11L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        assertEquals(2L, SendDelayAction.delayedPasses());

        tick.set(12L);
        budgets.set(List.of(budget(WORLD, 12L, 1L, false)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
        assertEquals(2L, SendDelayAction.delayedPasses(), "recovery does not erase the count");

        SendDelayAction.reset();
        assertEquals(0L, SendDelayAction.delayedPasses());

        budgets.set(List.of(budget(WORLD, 12L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        SendDelayAction.forgetWorld(WORLD);
        assertEquals(0L, SendDelayAction.delayedPasses(), "unload drops the count");

        budgets.set(List.of(budget(WORLD, 12L, 2L, true)));
        epochs.put(WORLD, 2L);
        SendDelayAction.install(true, tick::get,
            world -> epochs.getOrDefault(world, 0L), budgets::get);
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        assertEquals(1L, SendDelayAction.delayedPasses(), "a new epoch starts at one");
        SendDelayAction.install(false, null, null, null);
        assertEquals(0L, SendDelayAction.delayedPasses());
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
    }

    @Test
    void maxDelayForcesTheNextSendPassBeforeTheDelayCanArmAgain() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4), "the first pass is deferred");
        tick.set(13L);
        budgets.set(List.of(budget(WORLD, 13L, 1L, true)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4),
            "the max delay must force a send pass");
        tick.set(14L);
        budgets.set(List.of(budget(WORLD, 14L, 1L, true)));
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4),
            "the tick immediately after the forced pass must stay open");
        tick.set(15L);
        budgets.set(List.of(budget(WORLD, 15L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4),
            "after the forced window a fresh parent-over pass may arm again");
    }

    @Test
    void anUnreadableBudgetFallsOpenAndResetDropsTheState() {
        arm();
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        budgets.set(null);
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
        budgets.set(List.of(budget(WORLD, 10L, 1L, true)));
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        SendDelayAction.forgetWorld(WORLD);
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        SendDelayAction.reset();
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        SendDelayAction.install(false, null, null, null);
        assertFalse(SendDelayAction.shouldDefer(WORLD, 4));
    }
}
