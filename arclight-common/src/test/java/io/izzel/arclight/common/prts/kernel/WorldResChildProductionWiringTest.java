/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildBudget;
import io.izzel.arclight.common.prts.kernel.shares.WorldResChildLedger;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production wiring of the world-resource child settlement: the existing pipeline mailbox tap
 * reports real task nanoseconds into the meter, the existing budget boundary of the module drains
 * that window and settles it against the real share table's world-resource row, and the answer is
 * the internal ledger's settlement, not a test adapter. Only the mailbox objects, the world ids and
 * the measured nanoseconds are fixtures; the module, its planner and its table are the production
 * classes. */
class WorldResChildProductionWiringTest {

    private static final Path CONFIG_DIR = PrtsConfigManager.directory();
    private static final String WORLD_A = "world-a";
    private static final String WORLD_B = "world-b";
    private static final String WORLD_C = "world-c";

    private final KernelModule module = KernelModule.instance();
    private final Object mailboxA = new Object();
    private final Object mailboxB = new Object();
    private final Object mailboxC = new Object();

    @BeforeEach
    void switchTheKernelOn() throws IOException {
        deleteTree(CONFIG_DIR);
        Files.createDirectories(CONFIG_DIR);
        PrtsConfigManager.Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL);
        Files.writeString(CONFIG_DIR.resolve("kernel.yml"), PrtsConfigManager.defaults(entry),
            StandardCharsets.UTF_8);
        PrtsConfigManager.ensureAndLoad();
        System.setProperty("arclight.prts.kernel", "true");
        SelfTimers.resetAll();
        module.noteTickThread(Thread.currentThread());
        placeTheMailboxes();
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
    }

    @AfterEach
    void restoreTheDeclaredDefaults() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        module.removePipelineRowTap();
        module.resetReadings();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
        PrtsPipelineRows.install(null);
        SelfTimers.resetAll();
        deleteTree(CONFIG_DIR);
        PrtsConfigManager.ensureAndLoad();
        deleteTree(CONFIG_DIR);
    }

    private void tick(String... worlds) {
        module.serverTick(List.of(worlds));
    }

    private void placeTheMailboxes() {
        PrtsPipelineRows.bindMailboxWorlds(Map.of(mailboxA, WORLD_A, mailboxB, WORLD_B, mailboxC,
            WORLD_C));
    }

    private WorldResChildBudget budget(String world) {
        WorldResChildBudget found = null;
        for (WorldResChildBudget candidate : module.worldResChildLedger().settlements()) {
            if (candidate.worldId().equals(world)) {
                found = candidate;
            }
        }
        return found;
    }

    private static void assertConserved(WorldResChildBudget budget) {
        assertTrue(Double.isFinite(budget.parentShareMs()));
        assertTrue(Double.isFinite(budget.genShareMs()));
        assertTrue(Double.isFinite(budget.lightShareMs()));
        assertTrue(Double.isFinite(budget.unallocatedMs()));
        assertTrue(budget.conserved());
        assertEquals(budget.parentShareMs(), budget.plannedShareMs(),
            1.0e-9 * Math.max(1.0, budget.parentShareMs()));
    }

    @Test
    void theExistingBudgetBoundarySettlesTheRealParentRowAndTheMeasuredDemand() {
        tick(WORLD_A, WORLD_B, WORLD_C);
        long measuredTick = module.tickIndex();
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        PrtsPipelineRows.task(mailboxA, "light", 1_000_000L);
        PrtsPipelineRows.task(mailboxB, "worldgen", 1_000_000L);

        tick(WORLD_A, WORLD_B, WORLD_C);

        WorldResChildLedger ledger = module.worldResChildLedger();
        ShareTable table = module.shares().lastTable();
        assertEquals(3, ledger.settlementCount());
        assertEquals(3L * ShareClass.rowCount(), table.rows().size(),
            "the children are a partition of the world-resource row, not extra top-level rows");
        double parent = table.row(WORLD_A, ShareClass.WORLDRES).shareMs();

        WorldResChildBudget a = budget(WORLD_A);
        assertNotNull(a);
        assertEquals(measuredTick, a.tickIndex());
        assertEquals(parent, a.parentShareMs(), 1.0e-9);
        assertEquals(WorldResChildBudget.Status.SETTLED, a.status());
        assertEquals(WorldResChildBudget.SplitSource.DECLARED_TASK_TIME, a.splitSource());
        assertEquals(2_000_000.0, a.genTaskNanos(), 1.0e-9);
        assertEquals(1_000_000.0, a.lightTaskNanos(), 1.0e-9);
        assertEquals(2.0, a.genShareMs() / a.lightShareMs(), 1.0e-9);
        assertEquals(0.0, a.otherMs(), 0.0);
        assertEquals(0.0, a.marginMs(), 0.0);
        assertConserved(a);
        assertTrue(a.epoch() > 0L);
        assertEquals(a.epoch(), ledger.epochOf(WORLD_A));

        WorldResChildBudget b = budget(WORLD_B);
        assertEquals(1_000_000.0, b.genTaskNanos(), 1.0e-9);
        assertEquals(0.0, b.lightTaskNanos(), 0.0);
        assertEquals(WorldResChildBudget.Status.SETTLED, b.status());

        WorldResChildBudget c = budget(WORLD_C);
        assertEquals(WorldResChildBudget.Status.UNKNOWN_DEMAND, c.status(),
            "a world without a generation or light report is unknown, not a zero demand");
        assertEquals(0.0, c.childShareMs(), 0.0);
        assertEquals(c.parentShareMs(), c.unallocatedMs(), 1.0e-9);

        assertEquals(parent * 3.0, ledger.countedParentMs(), 1.0e-9,
            "each world's parent row is counted once, the children add nothing");
    }

    @Test
    void anUnloadedWorldIsDroppedAtTheBoundaryAndTheOtherWorldStillSettles() {
        tick(WORLD_A, WORLD_B);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        PrtsPipelineRows.task(mailboxB, "worldgen", 4_000_000L);

        module.flushWorldAtStop(WORLD_A);
        tick(WORLD_B);

        WorldResChildLedger ledger = module.worldResChildLedger();
        assertNull(budget(WORLD_A), "the unloaded world left no settlement behind");
        assertNotNull(budget(WORLD_B));
        assertEquals(4_000_000.0, budget(WORLD_B).genTaskNanos(), 1.0e-9);
        assertEquals(1, ledger.settlementCount());

        PrtsPipelineRows.task(mailboxA, "worldgen", 8_000_000L);
        tick(WORLD_B);

        assertNull(budget(WORLD_A), "a late report of the unloaded world enters no window");
        assertEquals(2, ledger.settlementCount(), "one settlement per live world per settled tick");
        assertEquals(WorldResChildBudget.Status.UNKNOWN_DEMAND, budget(WORLD_B).status());
    }


    @Test
    void aParentPipelineTaskFeedsTheWorldResourceUsedAndCanOverrunTheRow() {
        tick(WORLD_A);
        double parentShare = module.shares().lastTable()
            .row(WORLD_A, ShareClass.WORLDRES).shareMs();
        long parentNanos = (long) Math.ceil(parentShare * 2_000_000.0) + 1_000_000L;

        PrtsPipelineRows.task(mailboxA, "sorter", parentNanos);
        tick(WORLD_A);
        ShareTable.ShareRow usedRow = module.shares().lastTable()
            .row(WORLD_A, ShareClass.WORLDRES);
        assertTrue(usedRow.usedMs() > parentShare,
            "the parent-world-resource task is the row's own used time, not zero");

        tick(WORLD_A);
        WorldResChildBudget budget = budget(WORLD_A);
        assertNotNull(budget);
        assertEquals(WorldResChildBudget.Status.PARENT_OVER, budget.status(),
            "a parent own use above the row allowance is the explicit over status");
        assertTrue(budget.otherMs() > budget.parentShareMs());
        assertTrue(SendDelayAction.shouldDefer(WORLD_A, 4),
            "the fresh over settlement now reaches the bounded send-delay consumer");
    }

    @Test
    void parentAndChildPipelineTasksKeepSeparateUsedSources() {
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        PrtsPipelineRows.task(mailboxA, "sorter", 5_000_000L);
        tick(WORLD_A);
        tick(WORLD_A);

        WorldResChildBudget childWindow = null;
        WorldResChildBudget parentWindow = null;
        for (WorldResChildBudget candidate : module.worldResChildLedger().settlements()) {
            if (!candidate.worldId().equals(WORLD_A)) {
                continue;
            }
            if (candidate.tickIndex() <= 1L) {
                childWindow = candidate;
            } else {
                parentWindow = candidate;
            }
        }
        assertNotNull(childWindow);
        assertNotNull(parentWindow);
        assertEquals(2_000_000.0, childWindow.genTaskNanos(), 1.0e-9);
        assertEquals(0.0, childWindow.otherMs(), 1.0e-9);
        assertEquals(5.0, parentWindow.otherMs(), 1.0e-9,
            "only the non-child parent task is the parent own use");
        assertEquals(0.0, parentWindow.genTaskNanos(), 1.0e-9);
    }

    @Test
    void aReloadedWorldStartsAtANewEpochAndTheOldWindowIsNotSettled() {
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        KernelModule.WorldStopFlush flush = module.flushWorldAtStop(WORLD_A);
        long oldEpoch = flush.worldEpoch();

        tick(WORLD_A);
        assertTrue(module.worldResChildLedger().settlements().isEmpty(),
            "the window measured on the old generation is dropped, not settled under the new one");
        PrtsPipelineRows.task(mailboxA, "worldgen", 5_000_000L);
        tick(WORLD_A);

        WorldResChildBudget budget = budget(WORLD_A);
        assertNotNull(budget);
        assertTrue(budget.epoch() > oldEpoch, "the reloaded world carries a newer generation");
        assertEquals(5_000_000.0, budget.genTaskNanos(), 1.0e-9);
        assertEquals(1, module.worldResChildLedger().settlementCount());
    }

    @Test
    void theOtherPartOfTheParentRowComesFromItsMeteredUse() {
        SelfTimers.note(SelfClass.WORLDRES, WORLD_A, "prts-wiring-test", 100_000L);
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        PrtsPipelineRows.task(mailboxA, "light", 1_000_000L);

        tick(WORLD_A);

        WorldResChildBudget budget = budget(WORLD_A);
        assertEquals(0.1, budget.otherMs(), 1.0e-9,
            "the row's own metered use is the explicit parent other part");
        double available = budget.parentShareMs() - 0.1;
        assertEquals(available, budget.genShareMs() + budget.lightShareMs(), 1.0e-9,
            "the children split the row margin left after the other part");
        assertEquals(2.0, budget.genShareMs() / budget.lightShareMs(), 1.0e-9);
        assertConserved(budget);
    }

    @Test
    void theChildSplitAddsNoTopLevelRowAndKeepsTheOtherClassesAndThePlan() {
        tick(WORLD_A, WORLD_B);
        double plannedBefore = module.shares().lastTable().sumSharesMs();
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        tick(WORLD_A, WORLD_B);

        ShareTable table = module.shares().lastTable();
        assertEquals(2L * ShareClass.rowCount(), table.rows().size());
        assertEquals(plannedBefore, table.sumSharesMs(), 1.0e-9,
            "the child split does not rewrite the planned shares");
        assertEquals(KernelSettings.reserveMs(), table.reserve().reserveMs(), 0.0);
        assertTrue(module.shares().checkConservation(table).ok(),
            "the planned shares, reserve and host overhead still fit the tick budget");
        for (ShareClass shareClass : ShareClass.ASSERTED) {
            assertTrue(table.row(WORLD_A, shareClass).shareMs() > 0.0,
                "the asserted class rows keep their planned share");
        }
        ShareTable.ShareRow worldRes = table.row(WORLD_A, ShareClass.WORLDRES);
        assertTrue(worldRes.shareMs() > 0.0);
    }

    @Test
    void aClosedWindowIsNotMergedIntoTheNextOne() {
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        module.removePipelineRowTap();
        PrtsPipelineRows.task(mailboxA, "worldgen", 3_000_000L);
        tick(WORLD_A);
        // The fixture re-declares the mailbox placement after the tap round trip: in a host the
        // existing placement observer owns that binding, and this test only replaces its world.
        placeTheMailboxes();
        PrtsPipelineRows.task(mailboxA, "worldgen", 5_000_000L);
        tick(WORLD_A);

        WorldResChildBudget budget = budget(WORLD_A);
        assertNotNull(budget);
        assertEquals(5_000_000.0, budget.genTaskNanos(), 1.0e-9,
            "a window a removed producer closed is dropped, never merged into the next one");
    }

    @Test
    void aDisabledKernelDropsItsOpenWindowInsteadOfBackfilling() {
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        System.setProperty("arclight.prts.kernel", "false");
        tick(WORLD_A);
        System.setProperty("arclight.prts.kernel", "true");
        tick(WORLD_A);
        assertTrue(module.worldResChildLedger().settlements().isEmpty(),
            "the window the disabled tick closed is not settled");
        placeTheMailboxes();
        PrtsPipelineRows.task(mailboxA, "worldgen", 5_000_000L);
        tick(WORLD_A);

        WorldResChildBudget budget = budget(WORLD_A);
        assertNotNull(budget);
        assertEquals(5_000_000.0, budget.genTaskNanos(), 1.0e-9);
    }

    @Test
    void aResetClearsTheSettlementsAndTheEpochs() {
        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 2_000_000L);
        tick(WORLD_A);
        assertEquals(1, module.worldResChildLedger().settlementCount());

        module.resetReadings();

        WorldResChildLedger ledger = module.worldResChildLedger();
        assertEquals(0, ledger.settlementCount());
        assertEquals(0.0, ledger.countedParentMs(), 0.0);
        assertEquals(Long.MIN_VALUE, ledger.epochOf(WORLD_A));

        tick(WORLD_A);
        PrtsPipelineRows.task(mailboxA, "worldgen", 1_000_000L);
        tick(WORLD_A);
        assertEquals(1, ledger.settlementCount());
        assertEquals(1_000_000.0, budget(WORLD_A).genTaskNanos(), 1.0e-9);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
