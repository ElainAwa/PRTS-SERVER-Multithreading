/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WORLDRES child readout: aggregate only the latest current-epoch SETTLED B01-A settlement
 * per world; unknown/over/invalid and absent sources emit no synthetic zero. */
class P3B01EChildShareReadoutTest {

    private static final String A = "minecraft:overworld";
    private static final String B = "minecraft:the_end";

    private static ShareTable table(String world, long tick) {
        return new SharePlanner().plan(List.of(world), tick, Map.of());
    }

    private static WorldResChildBudget settled(String world, long tick, long epoch,
                                               long genNanos, long lightNanos) {
        ShareTable table = table(world, tick);
        return WorldResChildBudget.settle(table, world, epoch,
            WorldResChildBudget.ChildDemand.ofTaskNanos(genNanos, lightNanos), 0.0, 0.0);
    }

    private static WorldResChildBudget over(String world, long tick, long epoch) {
        ShareTable table = table(world, tick);
        double parent = table.row(world, ShareClass.WORLDRES).shareMs();
        return WorldResChildBudget.settle(table, world, epoch,
            WorldResChildBudget.ChildDemand.ofTaskNanos(1_000_000L, 500_000L),
            parent * 2.0, 0.0);
    }

    private static WorldResChildBudget unknown(String world, long tick, long epoch) {
        ShareTable table = table(world, tick);
        return WorldResChildBudget.settle(table, world, epoch,
            WorldResChildBudget.ChildDemand.unknown(), 0.0, 0.0);
    }

    private static WorldResChildBudget invalid(String world, long tick, long epoch) {
        ShareTable table = table(world, tick);
        return WorldResChildBudget.settle(table, world, epoch,
            WorldResChildBudget.ChildDemand.ofTaskNanos(1_000_000L, 500_000L),
            Double.NaN, 0.0);
    }

    private static List<String> lines(WorldResChildBudget... budgets) {
        List<String> out = new ArrayList<>();
        KernelReadings.worldresChildSignals(out, List.of(budgets), world -> 1L);
        return out;
    }

    @Test
    void liveSettlementsAggregateIntoTwoChildLines() {
        WorldResChildBudget a = settled(A, 1L, 1L, 2_000_000L, 1_000_000L);
        WorldResChildBudget b = settled(B, 1L, 1L, 4_000_000L, 2_000_000L);
        List<String> out = lines(a, b);

        assertTrue(out.contains("budget.worldres.gen_share_ms="
            + String.format(java.util.Locale.ROOT, "%.3f", a.genShareMs() + b.genShareMs())));
        assertTrue(out.contains("budget.worldres.light_share_ms="
            + String.format(java.util.Locale.ROOT, "%.3f", a.lightShareMs() + b.lightShareMs())));
    }

    @Test
    void onlyTheLatestTickerPerWorldWins() {
        WorldResChildBudget first = settled(A, 1L, 1L, 1_000_000L, 0L);
        WorldResChildBudget latest = settled(A, 2L, 1L, 3_000_000L, 2_000_000L);
        KernelReadings.WorldResChildShares shares = KernelReadings.worldResChildShares(
            List.of(first, latest), world -> 1L);
        assertNotNull(shares);
        assertEquals(latest.genShareMs(), shares.genShareMs(), 1.0e-9);
        assertEquals(latest.lightShareMs(), shares.lightShareMs(), 1.0e-9);
    }

    @Test
    void unknownOverParentAndInvalidSourcesDoNotPublishAZero() {
        assertNull(KernelReadings.worldResChildShares(List.of(unknown(A, 1L, 1L)), world -> 1L));
        assertNull(KernelReadings.worldResChildShares(List.of(over(A, 1L, 1L)), world -> 1L));
        assertNull(KernelReadings.worldResChildShares(List.of(invalid(A, 1L, 1L)), world -> 1L));
        assertTrue(lines(unknown(A, 1L, 1L)).isEmpty());
    }

    @Test
    void aKnownZeroSettlementPublishesRealZeroChildRows() {
        WorldResChildBudget zero = WorldResChildBudget.settle(table(A, 1L), A, 1L,
            WorldResChildBudget.ChildDemand.ofTaskNanos(0L, 0L), 0.0, 0.0);
        KernelReadings.WorldResChildShares shares = KernelReadings.worldResChildShares(
            List.of(zero), world -> 1L);
        assertNotNull(shares);
        assertEquals(0.0, shares.genShareMs(), 0.0);
        assertEquals(0.0, shares.lightShareMs(), 0.0);
        List<String> out = lines(zero);
        assertTrue(out.contains("budget.worldres.gen_share_ms=0.000"));
        assertTrue(out.contains("budget.worldres.light_share_ms=0.000"));
    }

    @Test
    void anOldEpochAndAnEmptyLedgerEmitNothing() {
        WorldResChildBudget old = settled(A, 1L, 1L, 2_000_000L, 1_000_000L);
        assertNull(KernelReadings.worldResChildShares(List.of(old), world -> 2L));
        List<String> oldLines = new ArrayList<>();
        KernelReadings.worldresChildSignals(oldLines, List.of(old), world -> 2L);
        assertTrue(oldLines.isEmpty());
        assertNull(KernelReadings.worldResChildShares(List.of(), world -> 1L));
        List<String> empty = new ArrayList<>();
        KernelReadings.worldresChildSignals(empty, List.of(), world -> 1L);
        assertTrue(empty.isEmpty());
    }

    @Test
    void theP2FiveClassSurfaceIsUnchanged() {
        assertEquals(8, ShareClass.values().length);
        assertEquals(5, ShareClass.ASSERTED.size());
        assertTrue(ShareClass.ASSERTED.contains(ShareClass.ENTITY));
        assertTrue(ShareClass.ASSERTED.contains(ShareClass.AI));
        assertFalse(ShareClass.ASSERTED.contains(ShareClass.WORLDRES));
    }
}
