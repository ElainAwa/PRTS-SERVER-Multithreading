/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded worker-side meter: only the two target names of a placed, target mailbox of a world
 * inside the window enter a bucket; an unplaced, non-target, wrong-world, negative, discontinuous or
 * closed report is refused and changes no bucket, and a drained window is never consumed twice. */
class WorldResChildMeterTest {

    private final Object targetMailbox = new Object();
    private final Object otherMailbox = new Object();

    @BeforeEach
    void placeTheMailboxes() {
        PrtsPipelineRows.bindMailboxWorlds(Map.of(targetMailbox, "world-a", otherMailbox,
            "world-b"));
    }

    @AfterEach
    void clearTheSeam() {
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());
        PrtsPipelineRows.bindNonTargetSorters(Set.of());
        PrtsPipelineRows.install(null);
    }

    @Test
    void onlyTheTwoTargetNamesOfOnePlacedWorldEnterItsBuckets() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(7L, Map.of("world-a", 3L, "world-b", 4L));

        assertTrue(meter.report(targetMailbox, "worldgen", 2_000_000L));
        assertTrue(meter.report(targetMailbox, "light", 1_000_000L));
        assertFalse(meter.report(targetMailbox, "sorter", 5L));
        assertFalse(meter.report(targetMailbox, null, 5L));

        WorldResChildMeter.WindowReading reading = meter.drain();
        assertEquals(7L, reading.tickIndex());
        WorldResChildMeter.Sample sample = reading.samples().get("world-a");
        assertEquals(2_000_000L, sample.genTaskNanos());
        assertEquals(1_000_000L, sample.lightTaskNanos());
        assertEquals(2L, sample.reports());
        assertEquals(3L, sample.epoch());
        assertNull(reading.samples().get("world-b"), "a world without a report has no bucket");
        assertEquals(2L, reading.rejectedNonTarget());
        assertEquals(Map.of("world-a", 3L, "world-b", 4L), reading.epochs());
    }

    @Test
    void unplacedNonTargetAndWrongWorldReportsDoNotEnter() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(7L, Map.of("world-a", 3L));
        Object unplaced = new Object();

        assertFalse(meter.report(unplaced, "worldgen", 10L));
        assertFalse(meter.report(otherMailbox, "worldgen", 10L));
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of(targetMailbox));
        assertFalse(meter.report(targetMailbox, "worldgen", 10L));
        PrtsPipelineRows.bindNonTargetMailboxes(Set.of());

        WorldResChildMeter.WindowReading reading = meter.drain();
        assertEquals(1L, reading.rejectedUnplaced());
        assertEquals(1L, reading.rejectedWrongWorld());
        assertEquals(1L, reading.rejectedNonTarget());
        assertTrue(reading.samples().isEmpty());
    }

    @Test
    void negativeAndDiscontinuousReportsDoNotEnter() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(7L, Map.of("world-a", 3L));

        assertFalse(meter.report(targetMailbox, "worldgen", -1L));
        assertFalse(meter.report(targetMailbox, "worldgen",
            WorldResChildMeter.MAX_SINGLE_REPORT_NANOS + 1L));
        assertTrue(meter.report(targetMailbox, "worldgen", WorldResChildMeter.MAX_SINGLE_REPORT_NANOS));

        WorldResChildMeter.WindowReading reading = meter.drain();
        assertEquals(1L, reading.rejectedNegative());
        assertEquals(1L, reading.rejectedDiscontinuity());
        assertEquals(WorldResChildMeter.MAX_SINGLE_REPORT_NANOS,
            reading.samples().get("world-a").genTaskNanos());
    }

    @Test
    void aReportAfterTheDrainIsClosedAndASecondDrainIsEmpty() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(7L, Map.of("world-a", 3L));
        assertTrue(meter.report(targetMailbox, "worldgen", 4L));

        WorldResChildMeter.WindowReading first = meter.drain();
        assertFalse(meter.report(targetMailbox, "worldgen", 5L));
        WorldResChildMeter.WindowReading second = meter.drain();

        assertEquals(4L, first.samples().get("world-a").genTaskNanos());
        assertEquals(1L, second.rejectedClosed());
        assertEquals(-1L, second.tickIndex());
        assertTrue(second.samples().isEmpty());
        assertEquals(4L, first.samples().get("world-a").genTaskNanos(),
            "the report consumed by the first drain is not consumed again");
    }

    @Test
    void openingWithoutDrainingDropsTheOlderWindow() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(1L, Map.of("world-a", 3L));
        assertTrue(meter.report(targetMailbox, "worldgen", 4L));

        meter.openWindow(2L, Map.of("world-a", 4L));
        WorldResChildMeter.WindowReading reading = meter.drain();

        assertEquals(2L, reading.tickIndex());
        assertTrue(reading.samples().isEmpty(), "an undrained window is dropped, never merged");
        assertEquals(1L, reading.droppedWindows());
        assertEquals(Map.of("world-a", 4L), reading.epochs(), "the new window pins the new epoch");
    }

    @Test
    void forgettingAWorldDropsItsSamplesAndRefusesItsLaterReports() {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(7L, Map.of("world-a", 3L, "world-b", 4L));
        assertTrue(meter.report(targetMailbox, "worldgen", 4L));

        meter.forgetWorld("world-a");
        assertFalse(meter.report(targetMailbox, "worldgen", 5L));

        WorldResChildMeter.WindowReading reading = meter.drain();
        assertTrue(reading.samples().isEmpty());
        assertEquals(1L, reading.rejectedWrongWorld());
        assertFalse(reading.epochs().containsKey("world-a"));
    }

    @Test
    void theBucketBoundRefusesTheWorldBeyondIt() {
        WorldResChildMeter meter = new WorldResChildMeter();
        java.util.Map<Object, String> placement = new java.util.IdentityHashMap<>();
        java.util.Map<String, Long> epochs = new java.util.LinkedHashMap<>();
        List<Object> mailboxes = new ArrayList<>();
        for (int index = 0; index <= WorldResChildMeter.MAX_WORLDS; index++) {
            Object mailbox = new Object();
            String world = "world-" + index;
            mailboxes.add(mailbox);
            placement.put(mailbox, world);
            epochs.put(world, 10L + index);
        }
        PrtsPipelineRows.bindMailboxWorlds(placement);
        meter.openWindow(8L, epochs);

        int accepted = 0;
        for (Object mailbox : mailboxes) {
            if (meter.report(mailbox, "worldgen", 1L)) {
                accepted++;
            }
        }

        WorldResChildMeter.WindowReading reading = meter.drain();
        assertEquals(WorldResChildMeter.MAX_WORLDS, accepted);
        assertEquals(WorldResChildMeter.MAX_WORLDS, reading.samples().size());
        assertEquals(1L, reading.rejectedCapacity());
    }

    @Test
    void concurrentReportsAcrossWindowsAreCountedExactlyOnce() throws Exception {
        WorldResChildMeter meter = new WorldResChildMeter();
        meter.openWindow(1L, Map.of("world-a", 3L));
        int threads = 4;
        int perThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<WorldResChildMeter.WindowReading> windows = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int step = 0; step < perThread; step++) {
                    if (meter.report(targetMailbox, "worldgen", 1L)) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        long tick = 2L;
        for (int round = 0; round < 500; round++) {
            WorldResChildMeter.WindowReading reading = meter.drain();
            if (reading.tickIndex() >= 0L) {
                windows.add(reading);
            }
            meter.openWindow(tick++, Map.of("world-a", 3L));
        }
        for (Future<?> future : futures) {
            future.get(30L, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        WorldResChildMeter.WindowReading last = meter.drain();
        if (last.tickIndex() >= 0L) {
            windows.add(last);
        }

        long reported = 0L;
        for (WorldResChildMeter.WindowReading reading : windows) {
            WorldResChildMeter.Sample sample = reading.samples().get("world-a");
            if (sample != null) {
                reported += sample.genTaskNanos();
            }
        }
        assertEquals(accepted.get(), reported,
            "each accepted one-nanosecond report is counted in exactly one window");
        assertEquals(threads * perThread, accepted.get() + last.rejectedClosed(),
            "every report is either accepted once or refused as closed");
    }

    @Test
    void theRowObserverHandsItsTapToTheMeterOnlyWhileItIsSet() {
        WorldResChildMeter meter = new WorldResChildMeter();
        PipelineRowObserver observer = new PipelineRowObserver();
        meter.openWindow(5L, Map.of("world-a", 9L));
        observer.childMeter(meter);

        observer.mailboxTask(targetMailbox, "worldgen", 7L);
        observer.childMeter(null);
        observer.mailboxTask(targetMailbox, "worldgen", 9L);

        WorldResChildMeter.Sample sample = meter.drain().samples().get("world-a");
        assertEquals(7L, sample.genTaskNanos());
        assertEquals(1L, sample.reports());
        assertEquals(2L, observer.tasks("worldgen"),
            "the row face keeps counting even while no meter is handed to it");
    }

    @Test
    void aReplacedProducerReachesOnlyTheMeterItCarries() {
        WorldResChildMeter meter = new WorldResChildMeter();
        PipelineRowObserver first = new PipelineRowObserver();
        PipelineRowObserver second = new PipelineRowObserver();
        first.childMeter(meter);
        meter.openWindow(5L, Map.of("world-a", 9L));

        PrtsPipelineRows.install(first);
        PrtsPipelineRows.task(targetMailbox, "worldgen", 3L);
        PrtsPipelineRows.install(second);
        PrtsPipelineRows.task(targetMailbox, "worldgen", 5L);

        WorldResChildMeter.Sample sample = meter.drain().samples().get("world-a");
        assertEquals(3L, sample.genTaskNanos(),
            "the replaced producer does not feed the meter it does not carry");
    }
}
