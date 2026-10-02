/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self timer under the concurrency the next layer brings.
 *
 * <p>The meters are per thread, but they are read and reset by another thread: the tick that plans the
 * budget walks every meter while the threads that record keep recording, and a worker that touches a
 * new world registers a meter while that walk is running. Nothing here may throw, and no sample that
 * was recorded before the walk may be missing from what the walk sums up.</p>
 */
class SelfTimersConcurrencyTest {

    private static final int WRITERS = 6;
    private static final int SAMPLES = 500;
    private static final long SAMPLE_NANOS = 1_000_000L;

    @AfterEach
    void reset() {
        SelfTimers.resetAll();
    }

    @Test
    void metersRegisteredWhileThePublicationWalksDoNotBreakItOrLoseSamples() throws Exception {
        SelfTimers.resetAll();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int index = 0; index < WRITERS; index++) {
            String world = "world-" + index;
            Thread writer = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int sample = 0; sample < SAMPLES; sample++) {
                        SelfTimers.note(SelfClass.ENTITY, world, "region-1", SAMPLE_NANOS);
                    }
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            }, "meter-writer-" + index);
            writers.add(writer);
            writer.start();
        }
        Thread reader = new Thread(() -> {
            try {
                go.await();
                for (int round = 0; round < 400; round++) {
                    SelfTimers.snapshot(round, round, false);
                    Map<String, long[]> totals = SelfTimers.consumeTickTotals();
                    for (long[] row : totals.values()) {
                        assertEquals(SelfClass.values().length, row.length);
                    }
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "meter-reader");
        reader.start();
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        for (Thread writer : writers) {
            writer.join();
        }
        reader.join();
        assertNull(failure.get(), "a concurrent publication must not fail");

        MeterWindow window = SelfTimers.snapshot(1L, 1, false);

        assertEquals((WRITERS * SAMPLES * SAMPLE_NANOS) / 1_000_000.0, window.totalMs(), 0.001,
            "every sample recorded before the walk is in the total the walk publishes");
        assertEquals((long) WRITERS * (SAMPLES - SelfTimers.RING), window.lostSamples(),
            "each writer keeps its own ring, so the overflow is counted per writer");
    }

    @Test
    void droppingTheTickTotalsClearsWhatAccumulatedWhileTheShareTableWasOff() {
        SelfTimers.resetAll();
        SelfTimers.note(SelfClass.ENTITY, "world", "region-1", 5_000_000L);
        assertEquals(5_000_000L, tickTotal(SelfTimers.consumeTickTotals()));

        SelfTimers.note(SelfClass.ENTITY, "world", "region-1", 7_000_000L);
        SelfTimers.discardTickTotals();

        assertEquals(0L, tickTotal(SelfTimers.consumeTickTotals()));
        assertEquals(12_000_000L, SelfTimers.snapshot(1L, 1, false).totalMs() * 1_000_000L,
            "the window total is not touched by dropping a tick");
    }

    private static long tickTotal(Map<String, long[]> totals) {
        long total = 0L;
        for (long[] row : totals.values()) {
            for (long value : row) {
                total += value;
            }
        }
        return total;
    }

}
