/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the chunk pipeline row counters make of the mailboxes the pipeline names. */
class PipelineRowObserverTest {

    @AfterEach
    void detach() {
        PrtsPipelineRows.install(null);
    }

    @Test
    void nothingIsCountedWhileNoWatcherIsInstalled() {
        assertFalse(PrtsPipelineRows.installed());
        PrtsPipelineRows.task(PipelineRowObserver.WORLDGEN, 10L);
        PrtsPipelineRows.round(PipelineRowObserver.WORLDGEN);
    }

    @Test
    void theTwoPipelineMailboxesAndTheTwoOtherNamedOnesLandInTheirOwnBuckets() {
        PipelineRowObserver observer = new PipelineRowObserver();
        observer.attach();
        assertSame(observer, PrtsPipelineRows.watcher());

        for (int index = 0; index < 3; index++) {
            PrtsPipelineRows.task(PipelineRowObserver.WORLDGEN, 1_000L);
        }
        PrtsPipelineRows.task(PipelineRowObserver.LIGHT, 2_000L);
        PrtsPipelineRows.task(PipelineRowObserver.SORTER, 0L);
        PrtsPipelineRows.task(PipelineRowObserver.MAIN, 0L);
        PrtsPipelineRows.task("load-progress", 0L);
        PrtsPipelineRows.task(null, 0L);
        for (int index = 0; index < 4; index++) {
            PrtsPipelineRows.round(PipelineRowObserver.WORLDGEN);
        }

        assertEquals(3L, observer.tasks(PipelineRowObserver.WORLDGEN));
        assertEquals(3_000L, observer.taskNanos(PipelineRowObserver.WORLDGEN));
        assertEquals(2_000L, observer.taskNanos(PipelineRowObserver.LIGHT));
        assertEquals(1L, observer.tasks(PipelineRowObserver.LIGHT));
        assertEquals(1L, observer.tasks(PipelineRowObserver.SORTER));
        assertEquals(1L, observer.tasks(PipelineRowObserver.MAIN));
        // A mailbox nobody named is not folded into a named one: both the unknown name and the
        // missing name answer the bucket of their own.
        assertEquals(2L, observer.tasks(PipelineRowObserver.OTHER));
        assertEquals(8L, observer.tasksTotal());
        assertEquals(4L, observer.rounds(PipelineRowObserver.WORLDGEN));
        assertEquals(0L, observer.rounds(PipelineRowObserver.LIGHT));
        assertEquals(4L, observer.roundsTotal());
    }

    @Test
    void detachingClearsTheSeamAndResetClearsTheCounters() {
        PipelineRowObserver observer = new PipelineRowObserver();
        observer.attach();
        PrtsPipelineRows.task(PipelineRowObserver.LIGHT, 5L);
        observer.detach();
        assertFalse(PrtsPipelineRows.installed());
        assertEquals(1L, observer.tasks(PipelineRowObserver.LIGHT));

        observer.reset();
        assertEquals(0L, observer.tasks(PipelineRowObserver.LIGHT));
        assertEquals(0L, observer.roundsTotal());
    }

    @Test
    void anotherOwnersSeamIsHandedBackUnchanged() {
        PipelineRowObserver other = new PipelineRowObserver();
        other.attach();
        PipelineRowObserver observer = new PipelineRowObserver();
        observer.detach();
        assertTrue(PrtsPipelineRows.installed());
        assertSame(other, PrtsPipelineRows.watcher());
    }
}
