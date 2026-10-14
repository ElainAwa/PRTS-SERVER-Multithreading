/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrtsChunkDemandAskGuardTest {

    private PrtsChunkDemand.DemandTap previous;

    @BeforeEach
    void rememberWatcher() {
        previous = PrtsChunkDemand.watcher();
    }

    @AfterEach
    void detach() {
        PrtsChunkDemand.install(previous);
    }

    private static final class ThrowingAskTap extends RecordingAskTap {

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            super.demandStarted(worldId, status, blocking);
            throw new IllegalStateException("no room for this ask");
        }
    }

    private static class RecordingAskTap implements PrtsChunkDemand.DemandTap {

        final List<String> asks = new ArrayList<>();

        @Override
        public void demandStarted(String worldId, String status, boolean blocking) {
            asks.add(worldId + " " + status + " " + blocking);
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
        }

        @Override
        public void futureCompleted(String worldId, String status, long startedNanos,
                                    boolean satisfied) {
        }
    }

    @Test
    void aChunklessAskUnderAThrowingWatchStillStarts() {
        ThrowingAskTap tap = new ThrowingAskTap();
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false),
            "an ask is not failed by the watch that saw it");
        assertEquals(List.of("minecraft:overworld full false"), tap.asks,
            "the watch was reached once: the guard drops the failure, not the event");
        assertSame(tap, PrtsChunkDemand.watcher(), "a failure does not detach the installed watch");
    }

    @Test
    void theAskAfterADroppedOneIsStillReported() {
        PrtsChunkDemand.install(new ThrowingAskTap());
        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false),
            "the dropped ask does not fail the ask itself");

        RecordingAskTap next = new RecordingAskTap();
        PrtsChunkDemand.install(next);
        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", true);
        assertEquals(List.of("minecraft:overworld full true"), next.asks,
            "the entry still reports the ask that follows a dropped one");
    }

    @Test
    void anErrorFromTheWatchDoesNotFailTheAsk() {
        RecordingAskTap tap = new RecordingAskTap() {
            @Override
            public void demandStarted(String worldId, String status, boolean blocking) {
                super.demandStarted(worldId, status, blocking);
                throw new AssertionError("the watch failed");
            }
        };
        PrtsChunkDemand.install(tap);

        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", true),
            "the guard covers errors from the watch as well as runtime exceptions");
        assertEquals(List.of("minecraft:overworld full true"), tap.asks,
            "a watch that raises an error is still reached exactly once");
    }

    @Test
    void anAskWithoutAWatchIsANoOp() {
        PrtsChunkDemand.install(null);

        assertDoesNotThrow(() -> PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false),
            "no installed watch means no observation");
        assertSame(null, PrtsChunkDemand.watcher(), "the entry does not install a watch");
    }

    @Test
    void aWorkingWatchReceivesBothFlagsUnchanged() {
        RecordingAskTap tap = new RecordingAskTap();
        PrtsChunkDemand.install(tap);

        PrtsChunkDemand.demandStarted("minecraft:overworld", "full", false);
        PrtsChunkDemand.demandStarted("minecraft:the_nether", "empty", true);
        assertEquals(List.of("minecraft:overworld full false", "minecraft:the_nether empty true"), tap.asks,
            "each call reports its world, status and blocking flag once");
    }

    @Test
    void aFailureInTheHostArgumentStillReachesTheCaller() {
        ThrowingAskTap tap = new ThrowingAskTap();
        PrtsChunkDemand.install(tap);
        IllegalStateException expected = new IllegalStateException("the host failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
            () -> PrtsChunkDemand.demandStarted(failingWorldId(expected), "full", false),
            "the guard covers only the watch call, not argument evaluation in the host");
        assertSame(expected, actual, "the host failure reaches its caller unchanged");
        assertEquals(List.of(), tap.asks, "argument evaluation fails before reaching the watch");
    }

    private static String failingWorldId(IllegalStateException failure) {
        throw failure;
    }
}
