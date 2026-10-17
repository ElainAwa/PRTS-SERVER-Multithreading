/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second path of the demand face: what the shadow reader is told, when a named ask may be read
 * by a freeze, and what a legacy tap still hears. The steps of one host call are written out here
 * as the two handlers of the mixin perform them, on the seam's own entries.
 */
class ChunkDemandSecondPathTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String STATUS = "full";
    private static final long EPOCH = 11L;
    private static final FrozenDeclarations.FreezeKey KEY =
        new FrozenDeclarations.FreezeKey("lifecycle-a", 4L);

    private final ChunkDemandObserver observer = new ChunkDemandObserver();
    private final DemandShadowCandidates shadows = new DemandShadowCandidates();

    @AfterEach
    void detach() {
        observer.detach();
        PrtsChunkDemand.install(null);
    }

    private void installBoth() {
        observer.attach();
        observer.attachSecondPath(shadows);
    }

    /** The head handler: it names the call and keeps the name for that same call. */
    private long head(int chunkX, int chunkZ) {
        return PrtsChunkDemand.demandDescriptorToken(WORLD, chunkX, chunkZ, STATUS, true);
    }

    /** The return handler as the mixin performs it, up to the registration it does not fail on. */
    private void hostCall(int chunkX, int chunkZ) {
        long token = head(chunkX, chunkZ);
        long stamp = PrtsChunkDemand.futureOpenedAt();
        if (stamp < 0L) {
            return;
        }
        PrtsChunkDemand.futureTaken(WORLD, STATUS, token);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
    }

    private List<DemandShadowCandidates.Shadow> read(long planSequence) {
        return shadows.readFor(new DemandShadowCandidates.Freeze(planSequence, 2L, KEY),
            worldId -> EPOCH);
    }

    @Test
    void oneAskIsCapturedOnceAndTheOldCountsAndTheStampAreKeptOnce() {
        installBoth();
        hostCall(3, -7);

        List<DemandShadowCandidates.Shadow> window = read(5L);

        assertEquals(1, window.size(), "one ask, one capture");
        assertTrue(window.get(0).declared());
        assertEquals(1L, observer.futuresTaken(), "the old count is kept once");
        assertEquals(0L, observer.futuresCompleted(), "nothing completed yet");
        assertEquals(1L, shadows.staged(), "the named ask was held while it was unconfirmed");
        assertEquals(1L, shadows.promoted(), "and confirmed exactly once");
        assertEquals(0, shadows.stagedHeld(), "a promoted capture is no longer held");
    }

    @Test
    void aNamedAskIsWrittenIntoTheWindowOnlyAfterItsRegistrationIsConfirmed() {
        installBoth();
        long token = head(3, -7);
        long stamp = PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.futureTaken(WORLD, STATUS, token);

        assertEquals(0, read(5L).size(), "an unconfirmed capture is in no window");
        assertEquals(1L, observer.futuresTaken(), "the old chain still ran");

        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
        assertEquals(1, read(6L).size(), "the confirmation is what makes it readable");
        assertTrue(stamp > 0L, "the stamp was taken once, as before");
    }

    @Test
    void twoAsksOfOneWorldAndStatusKeepTheirOwnName() {
        installBoth();
        long first = head(3, -7);
        long firstStamp = PrtsChunkDemand.futureOpenedAt();
        long second = head(4, -7);
        PrtsChunkDemand.futureOpenedAt();
        assertNotEquals(first, second, "two calls of one world and status are two names");

        PrtsChunkDemand.futureCompleted(WORLD, STATUS, firstStamp, true, first);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, second, true);

        List<DemandShadowCandidates.Shadow> window = read(5L);
        assertEquals(1, window.size(), "only the confirmed ask is in the window");
        assertFalse(window.get(0).late(),
            "the completion of the first ask did not reach the second one");
    }

    @Test
    void theIssuerNeverAnswersTheSameNameTwiceAndNeverAnswersNoName() {
        installBoth();
        long first = head(0, 0);
        long second = head(0, 0);

        assertTrue(first > 0L && second > 0L);
        assertNotEquals(first, second, "a name is handed out once");
        assertNotEquals(PrtsChunkDemand.NO_TOKEN, first);
    }

    @Test
    void anAskWithoutATapIsNotNamedAtAll() {
        PrtsChunkDemand.install(null);
        assertEquals(PrtsChunkDemand.NO_TOKEN,
            PrtsChunkDemand.demandDescriptorToken(WORLD, 3, -7, STATUS, true),
            "nothing is observed, so nothing is named");
        assertEquals(0L, shadows.staged());
    }

    @Test
    void anAskThatCarriesNoNameStillWalksTheOldChainExactlyOnce() {
        installBoth();
        long stamp = PrtsChunkDemand.futureOpenedAt();
        assertEquals(-1L, stamp, "no ask was opened, so there is no observation time");

        assertDoesNotThrow(() -> PrtsChunkDemand.futureTaken(WORLD, STATUS, PrtsChunkDemand.NO_TOKEN));
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, 1L, true, PrtsChunkDemand.NO_TOKEN);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, PrtsChunkDemand.NO_TOKEN, true);

        assertEquals(1L, observer.futuresTaken(), "the old count is kept once");
        assertEquals(1L, observer.futuresCompleted(), "and the old completion once");
        assertEquals(0L, shadows.staged(), "a call with no name is no capture of the second path");
        assertEquals(0, read(5L).size());
    }

    @Test
    void aRefusedStampDoesNotSpendAnOlderAsksObservationTime() {
        installBoth();
        for (int index = 0; index < 32; index++) {
            head(index, 0);
        }
        long refused = head(99, 0);

        assertEquals(-1L, PrtsChunkDemand.futureOpenedAt(),
            "the ask whose stamp was refused has no observation time of its own");
        long older = PrtsChunkDemand.futureOpenedAt();
        assertTrue(older > 0L, "an older ask keeps the stamp it was given");
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, refused, true);
        assertEquals(0L, shadows.promoted(), "the refused ask is still not a capture");
        while (PrtsChunkDemand.futureOpenedAt() > 0L) {
            // The remaining stamps belong to this case's own asks.
        }
    }

    @Test
    void aFullStampStackTellsNobodyAndCostsNoCapture() {
        installBoth();
        for (int index = 0; index < 32; index++) {
            head(index, 0);
        }
        long refused = head(99, 0);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, refused, true);

        assertEquals(32L, shadows.staged(), "an ask whose stamp was refused was never told on");
        assertEquals(0L, shadows.promoted(), "and no confirmation can build it an entry");
        assertEquals(32, shadows.stagedHeld(), "so it stays out of every window");
        assertEquals(0, read(5L).size(), "the window carries no capture of a refused stamp");
    }

    @Test
    void aConfirmationThatFailedPromotesNothing() {
        installBoth();
        long token = head(3, -7);
        PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, false);

        assertEquals(0L, shadows.promoted(), "a failed registration promotes nothing");
        assertEquals(0, read(5L).size());
    }

    @Test
    void aCompletionSeenBeforeTheConfirmationEntersTheWindowMarked() {
        installBoth();
        long token = head(3, -7);
        long stamp = PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.futureCompleted(WORLD, STATUS, stamp, true, token);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);

        List<DemandShadowCandidates.Shadow> window = read(5L);
        assertEquals(1, window.size(), "the capture is not thrown away");
        assertTrue(window.get(0).late(),
            "a completion that arrived before the confirmation is not read as outstanding");
        assertTrue(shadows.declarationsOf(window).isEmpty(), "so the whole window is refused");
    }

    @Test
    void aSecondConfirmationPromotesNothingAndRevivesNothing() {
        installBoth();
        long token = head(3, -7);
        PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token + 1000L, true);

        assertEquals(1L, shadows.promoted(), "one confirmation, one promotion");
        assertEquals(1, read(5L).size(), "and an unknown name rebuilds nothing");
    }

    @Test
    void aLateCompletionAfterTheWindowWasTakenMarksNothing() {
        installBoth();
        long token = head(3, -7);
        long stamp = PrtsChunkDemand.futureOpenedAt();
        PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
        read(5L);

        PrtsChunkDemand.futureCompleted(WORLD, STATUS, stamp, true, token);

        assertEquals(1L, shadows.completions(), "the completion is still told");
        assertEquals(0, read(6L).size(), "and finds no capture to mark");
        assertEquals(1L, shadows.captured(), "no entry was made for it");
    }

    @Test
    void aStagedAskIsDroppedByItsOwnBoundAndNotByTheRingCount() {
        installBoth();
        for (int index = 0; index < 65; index++) {
            head(index, 0);
            PrtsChunkDemand.futureOpenedAt();
        }

        assertEquals(65L, shadows.staged());
        assertEquals(64, shadows.stagedHeld(), "the staging area is bounded by the same capacity");
        assertEquals(0L, shadows.dropped(), "and its oldest entry is not a ring overwrite");
        assertEquals(0L, shadows.captured());
    }

    @Test
    void theDroppedCountStillCountsOnlyRingOverwrites() {
        installBoth();
        for (int index = 0; index < 65; index++) {
            hostCall(index, 0);
        }

        assertEquals(65L, shadows.promoted());
        assertEquals(1L, shadows.dropped(), "a full ring costs its oldest capture, as before");
    }

    @Test
    void theOverflowLatchStopsIssuingAndNeverWrapsAround() throws Exception {
        installBoth();
        AtomicLong counter = counter();
        AtomicLong saved = new AtomicLong(counter.get());
        try {
            counter.set(Long.MAX_VALUE);
            latch(false);
            assertEquals(PrtsChunkDemand.NO_TOKEN,
                PrtsChunkDemand.demandDescriptorToken(WORLD, 3, -7, STATUS, true),
                "the counter cannot name a call beyond its last value");
            assertEquals(Long.MIN_VALUE, counter.get(), "and it is not wrapped onto an old name");
            assertEquals(PrtsChunkDemand.NO_TOKEN,
                PrtsChunkDemand.demandDescriptorToken(WORLD, 4, -7, STATUS, true),
                "once stopped, it keeps answering no name");
            assertEquals(Long.MIN_VALUE, counter.get(), "without touching the counter again");
            assertTrue(latch(), "the latch is visible to the next caller");
            assertEquals(0L, shadows.staged(), "a call with no name is not a capture");
            assertEquals(0, shadows.stagedHeld());
        } finally {
            counter.set(saved.get());
            latch(false);
        }
    }

    @Test
    void asksOfManyThreadsEachKeepTheirOwnName() throws Exception {
        installBoth();
        int askers = 4;
        List<Long> names = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(askers);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int index = 0; index < askers; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                try {
                    ready.countDown();
                    go.await(30L, TimeUnit.SECONDS);
                    long token = head(slot, 0);
                    long stamp = PrtsChunkDemand.futureOpenedAt();
                    PrtsChunkDemand.futureTaken(WORLD, STATUS, token);
                    PrtsChunkDemand.registrationConfirmed(WORLD, STATUS, token, true);
                    synchronized (names) {
                        names.add(token);
                    }
                    assertTrue(stamp > 0L || stamp == -1L);
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await(30L, TimeUnit.SECONDS);
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30L));
            assertFalse(thread.isAlive(), "every asker returns within its timeout");
        }
        assertTrue(failures.isEmpty(), "no asker failed: " + failures);
        assertEquals(askers, names.size());
        assertEquals(askers, names.stream().distinct().count(), "each ask kept its own name");
        assertEquals(askers, shadows.promoted(), "and each confirmation promoted its own capture");
    }

    @Test
    void aNonBlockingAskIsStillToldToTheSecondPath() {
        int[] told = {0};
        observer.attach();
        observer.attachSecondPath(new PrtsChunkDemand.DemandTap() {

            @Override
            public void demandStarted(String worldId, String status, boolean blocking) {
                if (!blocking) {
                    told[0]++;
                }
            }

            @Override
            public void demandFinished(String worldId, String status, boolean blocking,
                                       boolean satisfied) {
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
            }

            @Override
            public void futureCompleted(String worldId, String status, long startedNanos,
                                        boolean satisfied) {
            }
        });

        PrtsChunkDemand.demandStarted(WORLD, STATUS, false);

        assertEquals(1, told[0], "a non-blocking ask is still one ask");
        assertEquals(1L, observer.requests(), "and the face still counts it");
        assertEquals(0L, observer.blockingRequests());
    }

    @Test
    void aThrowingSecondPathLeavesTheAskAndTheOldCountsAlone() {
        observer.attach();
        observer.attachSecondPath(new PrtsChunkDemand.DemandTap() {

            @Override
            public void demandStarted(String worldId, String status, boolean blocking) {
            }

            @Override
            public void demandFinished(String worldId, String status, boolean blocking,
                                       boolean satisfied) {
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
            }

            @Override
            public void futureCompleted(String worldId, String status, long startedNanos,
                                        boolean satisfied) {
            }

            @Override
            public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                         boolean requireChunk, long token) {
                throw new IllegalStateException("this reader is broken");
            }

            @Override
            public void registrationConfirmed(String worldId, String status, long token, boolean ok) {
                throw new IllegalStateException("this reader is broken");
            }
        });

        assertDoesNotThrow(() -> hostCall(3, -7), "a broken reader is not the ask failing");
        assertEquals(1L, observer.futuresTaken(), "the face counted the ask as always");
    }

    @SuppressWarnings("unchecked")
    private static AtomicLong counter() throws Exception {
        Field field = PrtsChunkDemand.class.getDeclaredField("callTokens");
        field.setAccessible(true);
        return (AtomicLong) field.get(null);
    }

    private static boolean latch() throws Exception {
        Field field = PrtsChunkDemand.class.getDeclaredField("tokensExhausted");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void latch(boolean value) throws Exception {
        Field field = PrtsChunkDemand.class.getDeclaredField("tokensExhausted");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }
}
