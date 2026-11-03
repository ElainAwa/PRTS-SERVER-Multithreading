/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal bounded event backlog: strict FIFO order across worlds, explicit capacity refusal
 * that raises the existing event queue rung, world/epoch attribution, unload/rebind/reset cleanup,
 * duplicate refusal and a bounded internal snapshot. No product path dispatches through it. */
class EventBacklogTest {

    private static final String WORLD_A = "world-a";
    private static final String WORLD_B = "world-b";

    private static EventBacklog backlog(int capacity) {
        EventBacklog backlog = new EventBacklog(capacity);
        assertTrue(backlog.bindWorld(WORLD_A, 1L));
        return backlog;
    }

    private static List<String> keys(List<EventBacklog.Event> events) {
        List<String> keys = new ArrayList<>();
        for (EventBacklog.Event event : events) {
            keys.add(event.key());
        }
        return keys;
    }

    @Test
    void eventsDrainInTheOrderTheyWereAcceptedAcrossWorlds() {
        EventBacklog backlog = backlog(16);
        assertTrue(backlog.bindWorld(WORLD_B, 2L));

        assertEquals(1L, backlog.enqueue(WORLD_A, 1L, "a-1", 5L).sequence());
        assertEquals(2L, backlog.enqueue(WORLD_B, 2L, "b-2", 5L).sequence());
        assertEquals(3L, backlog.enqueue(WORLD_A, 1L, "a-3", 5L).sequence());

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(3, snapshot.depth());
        assertEquals(3L, snapshot.accepted());
        assertEquals(1L, snapshot.firstSequence());
        assertEquals(3L, snapshot.lastSequence());
        assertEquals(List.of("a-1", "b-2", "a-3"), keys(backlog.drain(16)));
        assertEquals(3L, backlog.snapshot().drained());
        assertEquals(0, backlog.snapshot().depth());
    }

    @Test
    void aFullBacklogRefusesExplicitlyAndKeepsTheQueuedOrder() {
        EventBacklog backlog = backlog(2);
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 9L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-2", 9L).accepted());

        EventBacklog.Result refused = backlog.enqueue(WORLD_A, 1L, "a-3", 9L);

        assertEquals(EventBacklog.Rejection.FULL, refused.rejection());
        assertFalse(refused.accepted());
        assertTrue(refused.b4Signal());
        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(2, snapshot.depth());
        assertTrue(snapshot.atCapacity());
        assertEquals(1L, snapshot.rejectedFull());
        assertEquals(1L, snapshot.b4Signals());
        assertTrue(snapshot.b4Raised());
        assertEquals(List.of("a-1", "a-2"), keys(backlog.drain(2)));
        assertEquals(EventBacklog.Rejection.ACCEPTED,
            backlog.enqueue(WORLD_A, 1L, "a-3", 9L).rejection());
    }

    @Test
    void aFullBacklogRaisesTheExistingEventQueueRung() {
        DegradeLadder ladder = new DegradeLadder(() -> false);
        EventBacklog backlog = new EventBacklog(1, ladder);
        assertTrue(backlog.bindWorld(WORLD_A, 1L));
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 7L).accepted());

        EventBacklog.Result refused = backlog.enqueue(WORLD_A, 1L, "a-2", 7L);

        assertTrue(refused.b4Signal());
        assertEquals(DegradeLevel.B4, ladder.sign().deepest());
        assertEquals(1L, ladder.counters(DegradeLevel.B4).entered());
        assertEquals(0L, ladder.counters(DegradeLevel.B4).effective());
        assertEquals(1L, ladder.codeCount(RejectCode.QUEUE_CAP_EXCEEDED));
        assertEquals(EventBacklog.b4Rung(), ladder.rung(DegradeLevel.B4).level());
        assertEquals(EventBacklog.b4Code(), ladder.rung(DegradeLevel.B4).code());
        assertEquals("queue the events instead of dispatching them",
            ladder.rung(DegradeLevel.B4).action());
        assertEquals(1L, ladder.sign().skippedCount(),
            "a direct B4 signal records the skipped rungs instead of hiding them");
    }

    @Test
    void theBacklogRaisesTheRungButNeverRunsItsAction() {
        DegradeLadder ladder = new DegradeLadder(() -> true);
        EventBacklog backlog = new EventBacklog(1, ladder);
        assertTrue(backlog.bindWorld(WORLD_A, 1L));
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 7L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-2", 7L).b4Signal());

        assertEquals(1L, ladder.counters(DegradeLevel.B4).entered());
        assertEquals(0L, ladder.counters(DegradeLevel.B4).effective(),
            "the backlog queues and signals; it runs no dispatch action");
        assertEquals(0L, ladder.counters(DegradeLevel.B4).returned());
    }

    @Test
    void anUnboundWorldIsRefused() {
        EventBacklog backlog = backlog(4);

        EventBacklog.Result refused = backlog.enqueue("ghost", 1L, "g-1", 0L);

        assertEquals(EventBacklog.Rejection.WRONG_WORLD, refused.rejection());
        assertEquals(0, backlog.snapshot().depth());
        assertEquals(1L, backlog.snapshot().rejectedWrongWorld());
        assertEquals(EventBacklog.Rejection.WRONG_WORLD,
            backlog.enqueue(WORLD_B, 2L, "b-1", 0L).rejection());
    }

    @Test
    void aRebindDropsTheOldGenerationAndRefusesItsEvents() {
        EventBacklog backlog = backlog(4);
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-old", 0L).accepted());

        assertTrue(backlog.bindWorld(WORLD_A, 2L));

        assertEquals(0, backlog.snapshot().depth());
        assertEquals(1L, backlog.snapshot().droppedByRebind());
        assertEquals(EventBacklog.Rejection.STALE_EPOCH,
            backlog.enqueue(WORLD_A, 1L, "a-old-2", 0L).rejection());
        assertTrue(backlog.enqueue(WORLD_A, 2L, "a-new", 0L).accepted());
        assertEquals(1L, backlog.snapshot().rejectedStaleEpoch());
    }

    @Test
    void anUnknownNewerEpochAndAnOlderBindAreRefused() {
        EventBacklog backlog = backlog(4);

        assertEquals(EventBacklog.Rejection.WRONG_EPOCH,
            backlog.enqueue(WORLD_A, 2L, "a-future", 0L).rejection());
        assertFalse(backlog.bindWorld(WORLD_A, 0L));
        assertTrue(backlog.bindWorld(WORLD_A, 1L));

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(1L, snapshot.rejectedWrongEpoch());
        assertEquals(1L, snapshot.rejectedBindings());
        assertEquals(1L, snapshot.boundWorlds());
    }

    @Test
    void unloadingAWorldDropsItsEventsAndPreservesTheRemainingOrder() {
        EventBacklog backlog = backlog(8);
        assertTrue(backlog.bindWorld(WORLD_B, 2L));
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 0L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-2", 0L).accepted());
        assertTrue(backlog.enqueue(WORLD_B, 2L, "b-1", 0L).accepted());

        assertEquals(2, backlog.forgetWorld(WORLD_A));

        assertEquals(List.of("b-1"), keys(backlog.drain(8)));
        assertEquals(EventBacklog.Rejection.WRONG_WORLD,
            backlog.enqueue(WORLD_A, 1L, "a-3", 0L).rejection());
        assertEquals(2L, backlog.snapshot().droppedByForget());
    }

    @Test
    void resetClearsTheQueueBindingsAndCounters() {
        EventBacklog backlog = new EventBacklog(1);
        assertTrue(backlog.bindWorld(WORLD_A, 1L));
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 0L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-2", 0L).b4Signal());

        backlog.reset();

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(0, snapshot.depth());
        assertEquals(0, snapshot.boundWorlds());
        assertEquals(0L, snapshot.accepted());
        assertEquals(0L, snapshot.rejectedFull());
        assertEquals(0L, snapshot.b4Signals());
        assertFalse(snapshot.b4Raised());
        assertEquals(EventBacklog.Rejection.WRONG_WORLD,
            backlog.enqueue(WORLD_A, 1L, "a-3", 0L).rejection());
        assertTrue(backlog.bindWorld(WORLD_A, 1L));
        assertEquals(1L, backlog.enqueue(WORLD_A, 1L, "a-4", 0L).sequence());
    }

    @Test
    void aDuplicateKeyIsRefusedWhileQueuedAndAllowedAfterItDrains() {
        EventBacklog backlog = backlog(4);
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 0L).accepted());

        assertEquals(EventBacklog.Rejection.DUPLICATE,
            backlog.enqueue(WORLD_A, 1L, "a-1", 0L).rejection());
        assertEquals(1L, backlog.snapshot().rejectedDuplicate());
        assertTrue(backlog.bindWorld(WORLD_B, 1L));
        assertTrue(backlog.enqueue(WORLD_B, 1L, "a-1", 0L).accepted(),
            "the identity is scoped to the world and epoch");
        assertEquals(1, backlog.drain(1).size());
        assertEquals(EventBacklog.Rejection.ACCEPTED,
            backlog.enqueue(WORLD_A, 1L, "a-1", 0L).rejection());
    }

    @Test
    void peekAndDrainAreBoundedAndPeekDoesNotConsume() {
        EventBacklog backlog = backlog(3);
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-1", 0L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-2", 0L).accepted());
        assertTrue(backlog.enqueue(WORLD_A, 1L, "a-3", 0L).accepted());

        assertEquals(3, backlog.peek(10).size());
        assertEquals(List.of("a-1", "a-2", "a-3"), keys(backlog.peek(10)));
        assertEquals(3, backlog.snapshot().depth());
        assertEquals(0L, backlog.snapshot().drained());
        assertEquals(1, backlog.peek(1).size());
        assertEquals(List.of("a-1", "a-2"), keys(backlog.drain(2)));
        assertEquals(2L, backlog.snapshot().drained());
        assertEquals(List.of("a-3"), keys(backlog.peek(10)));
    }

    @Test
    void invalidInputsAndANonPositiveCapacityAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new EventBacklog(0));
        EventBacklog backlog = backlog(2);

        assertEquals(EventBacklog.Rejection.INVALID,
            backlog.enqueue(null, 1L, "k", 0L).rejection());
        assertEquals(EventBacklog.Rejection.INVALID,
            backlog.enqueue(" ", 1L, "k", 0L).rejection());
        assertEquals(EventBacklog.Rejection.INVALID,
            backlog.enqueue(WORLD_A, 1L, null, 0L).rejection());
        assertEquals(EventBacklog.Rejection.INVALID,
            backlog.enqueue(WORLD_A, 1L, " ", 0L).rejection());
        assertFalse(backlog.bindWorld(null, 1L));
        assertEquals(4L, backlog.snapshot().rejectedInvalid());
    }

    @Test
    void theWorldBindingIsBoundedAndRefusesTheWorldPastIt() {
        EventBacklog backlog = new EventBacklog(1);
        for (int index = 0; index < EventBacklog.MAX_WORLDS; index++) {
            assertTrue(backlog.bindWorld("world-" + index, 1L));
        }

        assertFalse(backlog.bindWorld("world-extra", 1L));

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(EventBacklog.MAX_WORLDS, snapshot.boundWorlds());
        assertEquals(1L, snapshot.rejectedBindings());
    }

    @Test
    void concurrentEnqueuesKeepStrictOrderAndBoundedDepth() throws Exception {
        EventBacklog backlog = backlog(512);
        int threads = 4;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            final int id = thread;
            futures.add(pool.submit(() -> {
                start.await();
                for (int step = 0; step < perThread; step++) {
                    if (backlog.enqueue(WORLD_A, 1L, "t" + id + "-" + step, 1L).accepted()) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30L, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(threads * perThread, accepted.get());
        assertEquals(threads * perThread, snapshot.accepted());
        assertEquals(0L, snapshot.rejectedFull());
        List<EventBacklog.Event> events = backlog.drain(threads * perThread);
        assertEquals(threads * perThread, events.size());
        long previous = -1L;
        for (EventBacklog.Event event : events) {
            assertTrue(event.sequence() > previous, "the drain order is the acceptance order");
            previous = event.sequence();
        }
    }

    @Test
    void concurrentPressureNeverExceedsTheCapacityAndRefusesExplicitly() throws Exception {
        EventBacklog backlog = backlog(8);
        int threads = 4;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refusedFull = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            final int id = thread;
            futures.add(pool.submit(() -> {
                start.await();
                for (int step = 0; step < perThread; step++) {
                    EventBacklog.Result result = backlog.enqueue(WORLD_A, 1L,
                        "t" + id + "-" + step, 1L);
                    if (result.accepted()) {
                        accepted.incrementAndGet();
                    } else {
                        assertEquals(EventBacklog.Rejection.FULL, result.rejection());
                        refusedFull.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30L, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        EventBacklog.Snapshot snapshot = backlog.snapshot();
        assertEquals(threads * perThread, accepted.get() + refusedFull.get());
        assertEquals(accepted.get(), snapshot.accepted());
        assertEquals(refusedFull.get(), snapshot.rejectedFull());
        assertEquals(refusedFull.get(), snapshot.b4Signals());
        assertEquals(8, snapshot.depth());
        assertTrue(snapshot.atCapacity());
    }

    @Test
    void noProductionPathUsesTheBacklog() throws IOException {
        Path sources = Path.of("src/main/java");
        if (!Files.exists(sources)) {
            sources = Path.of("arclight-common").resolve("src/main/java");
        }
        assertTrue(Files.isDirectory(sources), "the production sources are reachable");
        List<String> wired = new ArrayList<>();
        List<String> readoutKeys = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!file.getFileName().toString().equals("EventBacklog.java")
                    && text.contains("EventBacklog")) {
                    wired.add(sources.relativize(file).toString());
                }
                if (text.contains("event.backlog") || text.contains("event_backlog")) {
                    readoutKeys.add(sources.relativize(file).toString());
                }
            }
        }
        assertTrue(wired.isEmpty(), () -> "the backlog is internal, not dispatched: " + wired);
        assertTrue(readoutKeys.isEmpty(), () -> "no formal event backlog key: " + readoutKeys);
        assertNotNull(EventBacklog.class.getDeclaredMethods());
    }
}
