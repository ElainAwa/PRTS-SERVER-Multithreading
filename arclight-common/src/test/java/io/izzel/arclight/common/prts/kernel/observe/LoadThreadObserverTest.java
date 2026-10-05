/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsLoadProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the load sampler makes of a stack and of the wait a tick accumulated. */
class LoadThreadObserverTest {

    private static final long MILLIS = 1_000_000L;

    private static StackTraceElement frame(String owner, String method) {
        return new StackTraceElement(owner, method, owner + ".java", 1);
    }

    @Test
    void theStackSignaturesSplitTheFourWaitClasses() {
        assertEquals(LoadThreadObserver.DEPENDENCY, LoadThreadObserver.classify(new StackTraceElement[] {
            frame("java.util.concurrent.CompletableFuture", "join"),
            frame("net.minecraft.server.level.ChunkMap", "getChunkFutureMainThread"),
            frame("net.minecraft.server.level.ServerChunkCache", "getChunk")
        }));
        assertEquals(LoadThreadObserver.LOCK, LoadThreadObserver.classify(new StackTraceElement[] {
            frame("java.util.concurrent.locks.ReentrantLock", "lock"),
            frame("io.izzel.arclight.common.prts.kernel.commit.CommitLog", "closeTick")
        }));
        assertEquals(LoadThreadObserver.QUEUE, LoadThreadObserver.classify(new StackTraceElement[] {
            frame("java.util.concurrent.CompletableFuture", "join"),
            frame("net.minecraft.util.thread.BlockableEventLoop", "managedBlock")
        }));
        assertEquals(LoadThreadObserver.OTHER, LoadThreadObserver.classify(new StackTraceElement[] {
            frame("net.minecraft.server.MinecraftServer", "waitUntilNextTick")
        }));
        // A chunk dependency that is also queued is a dependency: the more specific signature wins.
        assertEquals(LoadThreadObserver.DEPENDENCY, LoadThreadObserver.classify(new StackTraceElement[] {
            frame("net.minecraft.util.thread.ProcessorMailbox", "pollTask"),
            frame("net.minecraft.server.level.ServerChunkCache", "getChunk")
        }));
    }

    @Test
    void consecutiveStalledTicksAreOneWindowAndAGapOpensTheNext() {
        LoadThreadObserver observer = new LoadThreadObserver();
        assertFalse(observer.startupStalled());
        observer.noteTickWaitNanos(60L * MILLIS);
        observer.noteTick(1L);
        observer.noteTickWaitNanos(70L * MILLIS);
        observer.noteTick(2L);
        assertEquals(1L, observer.stallWindows());
        assertEquals(2L, observer.stallTicks());
        assertEquals(1L, observer.stallEnteredTick());
        assertEquals(70L * MILLIS, observer.stallMaxNanos());
        assertEquals(130L * MILLIS, observer.stallNanos());
        assertTrue(observer.stallInWindow());

        observer.noteTick(3L);
        assertFalse(observer.stallInWindow());
        assertEquals(1L, observer.stallWindows());

        observer.noteTickWaitNanos(50L * MILLIS);
        observer.noteTick(4L);
        assertEquals(2L, observer.stallWindows());
        assertEquals(4L, observer.stallEnteredTick());
    }

    @Test
    void anInstalledSamplerIsWhatTheSeamTalksToAndTheStallClassIsNotASample() {
        PrtsLoadProbe.Probe previous = PrtsLoadProbe.watcher();
        PrtsLoadProbe.install(null);
        assertFalse(PrtsLoadProbe.installed());
        PrtsLoadProbe.tickEntered();
        PrtsLoadProbe.tickLeft();
        LoadThreadObserver observer = new LoadThreadObserver();
        observer.install();
        assertSame(observer, PrtsLoadProbe.watcher());
        assertTrue(observer.installed());
        PrtsLoadProbe.tickEntered();
        assertTrue(observer.firstTickSeen());
        PrtsLoadProbe.tickLeft();
        observer.uninstall();
        assertFalse(PrtsLoadProbe.installed());
        observer.reset();
        assertEquals(0L, observer.stallWindows());
        assertEquals(0L, observer.samples());
        PrtsLoadProbe.install(previous);
    }
}
