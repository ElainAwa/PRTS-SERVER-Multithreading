/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.SendDelayAction;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The formal delayed-send readout: it is emitted only while the action is installed and carries
 * the real cancelled-send-pass count, not a synthesized zero for an off action. */
class P3A02iDelayReadoutTest {

    private static final String WORLD = "minecraft:overworld";
    private final AtomicLong tick = new AtomicLong(10L);
    private final AtomicReference<List<WorldResChildBudget>> budgets = new AtomicReference<>(List.of());

    @AfterEach
    void cleanUp() {
        SendDelayAction.install(false, null, null, null);
    }

    private void arm() {
        ShareTable table = new SharePlanner().plan(List.of(WORLD), 10L, Map.of());
        double parent = table.row(WORLD, ShareClass.WORLDRES).shareMs();
        WorldResChildBudget over = WorldResChildBudget.settle(table, WORLD, 1L,
            WorldResChildBudget.ChildDemand.ofTaskNanos(1_000_000L, 500_000L), parent * 2.0, 0.0);
        budgets.set(List.of(over));
        SendDelayAction.install(true, tick::get, world -> 1L, budgets::get);
    }

    @Test
    void theReadoutCarriesTheActualDelayedPassCount() {
        arm();
        assertTrue(SendDelayAction.shouldDefer(WORLD, 4));
        List<String> lines = new ArrayList<>();
        KernelReadings.delaySendCount(lines);
        assertEquals(List.of("delay.send_count=1"), lines);
    }

    @Test
    void anOffActionOmitsTheKeyInsteadOfPublishingZero() {
        List<String> lines = new ArrayList<>();
        KernelReadings.delaySendCount(lines);
        assertTrue(lines.isEmpty());
    }
}
