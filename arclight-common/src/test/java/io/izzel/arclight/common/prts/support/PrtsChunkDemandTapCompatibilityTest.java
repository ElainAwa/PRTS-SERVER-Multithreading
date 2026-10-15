/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a tap is told when the chunk cache reports an ask: the chunk the ask named on the entries
 * that carry it, the same single count for a tap that only knows the world and the status, and the
 * ask left alone when the tap fails, asks again from inside its own answer, or asks from another
 * thread. */
class PrtsChunkDemandTapCompatibilityTest {

    private static final long TIMEOUT_SECONDS = 30L;

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(null);
    }

    /** One ask as the seam handed it over, with the entry it came through. */
    private record Ask(String worldId, int chunkX, int chunkZ, String status, boolean requireChunk,
                       boolean future) {
    }

    /** A tap that answers both shapes and keeps what it was told; the old counts stay at zero,
     * because an entry that is implemented is never reached through its default. */
    private static final class RecordingTap implements PrtsChunkDemand.DemandTap {

        final List<Ask> asks = Collections.synchronizedList(new ArrayList<>());
        final LongAdder oldStarted = new LongAdder();
        final LongAdder oldOpened = new LongAdder();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            oldStarted.increment();
        }

        @Override
        public void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                                  boolean requireChunk) {
            asks.add(new Ask(worldId, chunkX, chunkZ, status, requireChunk, false));
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
            oldOpened.increment();
        }

        @Override
        public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                     boolean requireChunk) {
            asks.add(new Ask(worldId, chunkX, chunkZ, status, requireChunk, true));
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A tap written against the seam as it was: it knows the world and the status, and nothing else. */
    private static final class LegacyTap implements PrtsChunkDemand.DemandTap {

        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final LongAdder blocking = new LongAdder();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            events.add("started " + worldId + " " + status);
            if (blocking) {
                this.blocking.increment();
            }
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
            events.add("opened " + worldId + " " + status);
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A tap that fails on both asks, in the shape it was written against. */
    private static final class FailingTap implements PrtsChunkDemand.DemandTap {

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            throw new IllegalStateException("no room for this ask");
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
            throw new IllegalStateException("no room for this ask");
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A tap that asks the seam once from inside its own answer, as a nested ask of the same thread
     * arrives; the nesting is kept, so the case can read how deep the seam was when it answered. */
    private static final class ReentrantTap implements PrtsChunkDemand.DemandTap {

        final List<Ask> asks = new ArrayList<>();
        final List<Integer> depths = new ArrayList<>();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
        }

        @Override
        public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                     boolean requireChunk) {
            depths.add(asks.size() + 1);
            asks.add(new Ask(worldId, chunkX, chunkZ, status, requireChunk, true));
            if (asks.size() == 1) {
                PrtsChunkDemand.demandDescriptor(worldId, chunkX + 1, chunkZ + 1, status, requireChunk);
            }
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    /** A tap that knows only the entries the seam had before this stage: it is told through the
     * defaults of the named ones, once each. */
    private static final class OldOnlyTap implements PrtsChunkDemand.DemandTap {

        final LongAdder descriptor = new LongAdder();
        final LongAdder taken = new LongAdder();
        final LongAdder completed = new LongAdder();
        final List<String> descriptorChunks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
        }

        @Override
        public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
        }

        @Override
        public void futureOpened(String worldId, String status) {
        }

        @Override
        public long futureOpenedAt() {
            return -1L;
        }

        @Override
        public void futureTaken(String worldId, String status) {
            taken.increment();
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
            completed.increment();
        }

        @Override
        public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                     boolean requireChunk) {
            descriptor.increment();
            descriptorChunks.add(chunkX + ":" + chunkZ);
        }
    }

    @Test
    void aTapThatKnowsOnlyTheOldEntriesIsStillToldTheNamedOnesOnce() {
        OldOnlyTap tap = new OldOnlyTap();
        PrtsChunkDemand.install(tap);

        long token = PrtsChunkDemand.demandDescriptorToken("minecraft:overworld", 3, -7, "full", true);
        PrtsChunkDemand.futureTaken("minecraft:overworld", "full", token);
        PrtsChunkDemand.futureCompleted("minecraft:overworld", "full", 1L, true, token);
        PrtsChunkDemand.registrationConfirmed("minecraft:overworld", "full", token, true);

        assertNotEquals(PrtsChunkDemand.NO_TOKEN, token, "the ask is named");
        assertEquals(1L, tap.descriptor.sum(), "the named entry bridges to the old one exactly once");
        assertEquals(1L, tap.taken.sum());
        assertEquals(1L, tap.completed.sum());
        assertEquals(List.of("3:-7"), tap.descriptorChunks,
            "and the chunk of the call reaches the old entry unchanged");
    }

    @Test
    void theChunkAnAskNamesReachesTheTapJustAsItWasAsked() {
        RecordingTap tap = new RecordingTap();
        PrtsChunkDemand.install(tap);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 3, -7, "full", true);
        PrtsChunkDemand.demandStarted("minecraft:overworld", 3, -7, "full", false);

        assertEquals(2, tap.asks.size(), "one ask through each entry, and no ask through both");
        Ask future = tap.asks.get(0);
        assertEquals("minecraft:overworld", future.worldId());
        assertEquals(3, future.chunkX());
        assertEquals(-7, future.chunkZ());
        assertEquals("full", future.status());
        assertTrue(future.requireChunk());
        assertTrue(future.future());
        Ask sync = tap.asks.get(1);
        assertEquals(3, sync.chunkX());
        assertEquals(-7, sync.chunkZ());
        assertFalse(sync.requireChunk());
        assertFalse(sync.future());
        assertEquals(0L, tap.oldOpened.sum(), "the entry that carries the chunk is the only one told");
        assertEquals(0L, tap.oldStarted.sum(), "the entry that carries the chunk is the only one told");
    }

    @Test
    void aTapThatKnowsOnlyTheWorldAndTheStatusIsToldOncePerAsk() {
        LegacyTap tap = new LegacyTap();
        PrtsChunkDemand.install(tap);
        PrtsChunkDemand.demandDescriptor("minecraft:the_nether", 12, 34, "features", true);
        PrtsChunkDemand.demandStarted("minecraft:the_nether", 12, 34, "features", true);

        assertEquals(List.of("opened minecraft:the_nether features", "started minecraft:the_nether features"),
            tap.events, "the default drops the chunk and keeps exactly the one old count per ask");
        assertEquals(1L, tap.blocking.sum(), "the flag the ask carried reaches the old entry");
    }

    @Test
    void theOriginAndNegativeChunksAreCarriedUnchanged() {
        RecordingTap tap = new RecordingTap();
        PrtsChunkDemand.install(tap);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 0, 0, "full", true);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", -1, -1, "full", true);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", -512, 1024, "full", true);

        assertEquals(3, tap.asks.size(), "the origin is an ask like any other, not an absent one");
        assertEquals(List.of("0", "0", "-1", "-1", "-512", "1024"),
            List.of(String.valueOf(tap.asks.get(0).chunkX()), String.valueOf(tap.asks.get(0).chunkZ()),
                String.valueOf(tap.asks.get(1).chunkX()), String.valueOf(tap.asks.get(1).chunkZ()),
                String.valueOf(tap.asks.get(2).chunkX()), String.valueOf(tap.asks.get(2).chunkZ())),
            "both coordinates arrive as they were asked, sign and zero included");
    }

    @Test
    void anObservationThatThrowsLeavesTheAskAndTheNextOneAlone() {
        PrtsChunkDemand.install(new FailingTap());
        assertDoesNotThrow(() -> PrtsChunkDemand.demandDescriptor("minecraft:overworld", 1, 2, "full", true),
            "an ask is not failed by the tap that watches it");
        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", 1, 2, "full", true),
            "an ask is not failed by the tap that watches it");

        RecordingTap next = new RecordingTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 1, 2, "full", true);
        assertEquals(1, next.asks.size(), "the seam still reports the ask that follows a failed one");
    }

    @Test
    void anAskThatArrivesFromInsideAnAnswerIsItsOwnAsk() {
        ReentrantTap tap = new ReentrantTap();
        PrtsChunkDemand.install(tap);
        PrtsChunkDemand.demandDescriptor("minecraft:overworld", 5, 6, "full", true);

        assertEquals(2, tap.asks.size(), "the nested ask is counted beside the ask it arrived in");
        assertEquals(List.of(1, 2), tap.depths, "the nesting is read one level at a time");
        assertEquals("5:6", tap.asks.get(0).chunkX() + ":" + tap.asks.get(0).chunkZ());
        assertEquals("6:7", tap.asks.get(1).chunkX() + ":" + tap.asks.get(1).chunkZ(),
            "the nested ask carries the chunk of the call it was made with");
    }

    @Test
    void asksFromManyThreadsEachKeepTheirOwnChunk() throws Exception {
        RecordingTap tap = new RecordingTap();
        PrtsChunkDemand.install(tap);
        int askers = 4;
        int perAsker = 250;
        CyclicBarrier barrier = new CyclicBarrier(askers + 1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < askers; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    for (int round = 0; round < perAsker; round++) {
                        PrtsChunkDemand.demandDescriptor("minecraft:overworld", slot, round, "full", true);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            threads.add(thread);
            thread.start();
        }
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "every asker returns within its timeout");
        }
        assertTrue(failures.isEmpty(), "no asker failed: " + failures);

        assertEquals(askers * perAsker, tap.asks.size(), "no ask is lost and none is counted twice");
        Set<String> chunks = new HashSet<>();
        for (Ask ask : tap.asks) {
            chunks.add(ask.chunkX() + ":" + ask.chunkZ());
        }
        assertEquals(askers * perAsker, chunks.size(), "each ask kept the chunk it was asked for");
    }
}
