/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.observe.PipelineRowObserver;
import net.minecraft.util.thread.ProcessorMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One target sorter mailbox and the ticket throttler mailbox share the name sorter; only the
 * object identity decides which one is the target account. */
class PrtsPipelineRowsNonTargetTest {

    private static final String WORLD = "minecraft:overworld";

    private static final Executor DIRECT = Runnable::run;

    private PipelineRowObserver rows;

    private PipelineRoundJobs jobs;

    @AfterEach
    void clear() {
        PrtsPipelineRows.install(null);
        PrtsPipelineRows.installOwnerTap(null);
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
        PrtsPipelineBatches.uninstall();
    }

    @Test
    void theTicketMailboxIsRecordedInOtherAndKeepsEveryIdentity() {
        ProcessorMailbox<Runnable> target = ProcessorMailbox.create(DIRECT, "sorter");
        ProcessorMailbox<Runnable> ticket = ProcessorMailbox.create(DIRECT, "sorter");
        rows = new PipelineRowObserver();
        rows.attach();
        jobs = new PipelineRoundJobs();
        PrtsPipelineRows.installOwnerTap(jobs);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(target, WORLD, ticket, WORLD));
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of(ticket));

        PrtsPipelineRows.round(target, "sorter");
        PrtsPipelineRows.ownerRound(target, "sorter");
        PrtsPipelineRows.round(ticket, "sorter");
        PrtsPipelineRows.ownerRound(ticket, "sorter");
        PrtsPipelineRows.task(target, "sorter", 5L);
        PrtsPipelineRows.ownerTask(target, "sorter");
        PrtsPipelineRows.task(ticket, "sorter", 7L);
        PrtsPipelineRows.ownerTask(ticket, "sorter");

        assertEquals(1L, rows.rounds(PipelineRowObserver.SORTER),
            "the target mailbox keeps the sorter row");
        assertEquals(1L, rows.rounds(PipelineRowObserver.OTHER),
            "the ticket mailbox is recorded in the other row, never in sorter");
        assertEquals(2L, rows.roundsTotal());
        assertEquals(1L, jobs.roundsSeen(), "the ticket round is not a target seen round");
        assertEquals(1L, jobs.roundsUnplaced(), "the ticket round is the non-target unplaced part");
        assertEquals(rows.rounds(PipelineRowObserver.SORTER), jobs.roundsSeen());
        assertEquals(rows.rounds(PipelineRowObserver.OTHER), jobs.roundsUnplaced());
        assertEquals(rows.roundsTotal(), jobs.roundsSeen() + jobs.roundsUnplaced());
        assertEquals(1L, rows.tasks(PipelineRowObserver.SORTER));
        assertEquals(1L, rows.tasks(PipelineRowObserver.OTHER));
        assertEquals(2L, jobs.tasksSeen());
    }

    @Test
    void aTicketMailboxWithAWorldStillNeverCountsAsSeen() {
        ProcessorMailbox<Runnable> ticket = ProcessorMailbox.create(DIRECT, "sorter");
        jobs = new PipelineRoundJobs();
        PrtsPipelineRows.installOwnerTap(jobs);
        PrtsPipelineRows.bindMailboxWorlds(Map.of(ticket, WORLD));
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of(ticket));

        PrtsPipelineRows.ownerRound(ticket, "sorter");

        assertEquals(0L, jobs.roundsSeen(), "classification wins over a later placement");
        assertEquals(1L, jobs.roundsUnplaced());
    }

    @Test
    void withoutClassificationTheRowsAndTheOwnerFaceBehaveAsBefore() {
        ProcessorMailbox<Runnable> mailbox = ProcessorMailbox.create(DIRECT, "sorter");
        rows = new PipelineRowObserver();
        rows.attach();
        jobs = new PipelineRoundJobs();
        PrtsPipelineRows.installOwnerTap(jobs);

        PrtsPipelineRows.round(mailbox, "sorter");
        PrtsPipelineRows.ownerRound(mailbox, "sorter");

        assertEquals(1L, rows.rounds(PipelineRowObserver.SORTER),
            "with no classification the named bucket is used, as before");
        assertEquals(0L, rows.rounds(PipelineRowObserver.OTHER));
        assertEquals(0L, jobs.roundsSeen());
        assertEquals(1L, jobs.roundsUnplaced(), "an unplaced mailbox is unplaced, as before");
    }

    @Test
    void aRebindIsByIdentityAndDetachLeavesNoClassification() {
        ProcessorMailbox<Runnable> first = ProcessorMailbox.create(DIRECT, "sorter");
        ProcessorMailbox<Runnable> second = ProcessorMailbox.create(DIRECT, "sorter");
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of(first));
        assertEquals(true, PrtsPipelineRows.isNonTargetMailbox(first));
        assertEquals(false, PrtsPipelineRows.isNonTargetMailbox(second),
            "a second object of the same name is not classified");
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        assertEquals(false, PrtsPipelineRows.isNonTargetMailbox(first));
    }
}
