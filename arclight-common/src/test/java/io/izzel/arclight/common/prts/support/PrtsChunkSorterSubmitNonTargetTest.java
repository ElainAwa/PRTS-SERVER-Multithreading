/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The submit side: a sorter object the placement walk classified as non-target never mints a
 * target task id, even while the batch sink is installed. */
class PrtsChunkSorterSubmitNonTargetTest {

    private static final String WORLD = "minecraft:overworld";

    private static final Executor DIRECT = Runnable::run;

    private int mints;

    @AfterEach
    void clear() {
        PrtsPipelineBatches.uninstall();
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
    }

    @Test
    void theTicketSorterNeverMintsATargetTaskId() {
        ProcessorMailbox<Runnable> target = ProcessorMailbox.create(DIRECT, "worldgen");
        PrtsPipelineRows.bindMailboxWorlds(Map.of(target, WORLD));
        Object ticketSorter = new Object();
        PrtsPipelineRows.bindNonTargetSorters(Set.of(ticketSorter));
        installSink();
        Function<ProcessorHandle<Unit>, Runnable> task = ignored -> () -> {
        };

        Function<ProcessorHandle<Unit>, Runnable> ticketWrapped =
            PrtsChunkSorterSubmit.wrap(ticketSorter, target, task, 1L);
        assertSame(task, ticketWrapped, "the ticket submit is handed back unminted");
        assertEquals(0, mints);
        Function<ProcessorHandle<Unit>, Runnable> targetWrapped =
            PrtsChunkSorterSubmit.wrap(new Object(), target, task, 1L);
        assertEquals(1, mints, "a target submit still mints while the sink is installed");
        assertSame(task, targetWrapped, "the fake sink wraps around the same delegate");
    }

    @Test
    void aTicketSorterWithoutASinkIsAlsoHandedBack() {
        Function<ProcessorHandle<Unit>, Runnable> task = ignored -> () -> {
        };
        assertSame(task, PrtsChunkSorterSubmit.wrap(new Object(), null, task, 1L));
        assertEquals(0, mints);
    }

    private void installSink() {
        PrtsPipelineBatches.install(new PrtsPipelineBatches.TaskSink() {
            @Override
            public Object mint(Object processor, String worldId, String mailbox, long cell,
                               long tick) {
                mints++;
                return new Object();
            }

            @Override
            public <T> Function<ProcessorHandle<Unit>, T> wrap(
                Object task, Function<ProcessorHandle<Unit>, T> delegate) {
                return delegate;
            }

            @Override
            public void enqueued(Object task) {
            }

            @Override
            public void released(Object processor, long cell, boolean clearQueue) {
            }
        }, () -> 7L);
    }
}
