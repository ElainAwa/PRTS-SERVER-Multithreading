/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision matrix of the write paths the host actually runs. */
class WorldWriteGuardTest {

    private static final long TICK = 100L;
    private static final Object LEVEL = new Object();

    @Test
    void theServerThreadTakesTheShortPathAndIsCountedThere() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, false, false, false, TICK);

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(1L, scratch.grants(ThreadOrigin.MAIN, HolderKind.REGISTERED));
        assertEquals(1L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED));
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void anUndeclaredThreadIsCountedAndItsWriteStillProceeds() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, false, false, false, TICK);
        Probe probe = scratch.probe();

        assertEquals(1, probe.probe.get());
        assertTrue(probe.proceeded);
        assertEquals(1L, scratch.counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER,
            HolderKind.UNREGISTERED, WriteDisposition.INTENT));
        assertEquals(0L, scratch.grants(ThreadOrigin.WORKER, HolderKind.UNREGISTERED));
        assertEquals(1L, scratch.counters.unregisteredAttempts());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.WRITE_DENIED_NOT_OWNER));
        assertNotNull(scratch.guard.lastDecision());
        assertEquals(WritePath.BLOCK_WRITE, scratch.guard.lastDecision().path());
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void enforcementRefusesAnUndeclaredWrite() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, true, false, false, TICK);
        Probe probe = scratch.probe();

        assertFalse(probe.proceeded);
        assertEquals(1L, scratch.counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER,
            HolderKind.UNREGISTERED, WriteDisposition.DENY));
        assertEquals(WriteDisposition.DENY, scratch.guard.lastDecision().disposition());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, scratch.guard.lastDecision().code());
    }

    @Test
    void aDeclaredWriterWithoutAVersionIsRefusedButObserved() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, false, false, false, TICK);
        Probe probe = scratch.probe(holder -> scratch.guard.registerHolder(holder,
            HolderKind.REGISTERED, "site:worker"));

        assertTrue(probe.proceeded);
        assertEquals(1L, scratch.counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER,
            HolderKind.REGISTERED, WriteDisposition.DENY));
        assertEquals(RejectCode.VERSION_MISMATCH, scratch.guard.lastDecision().code());
        assertEquals(0L, scratch.grants(ThreadOrigin.WORKER, HolderKind.REGISTERED));
        assertEquals(0L, scratch.counters.unregisteredAttempts());
    }

    @Test
    void aHandedOverWriteIsAppliedByTheCommitSegment() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, false, true, true, TICK);
        Probe probe = scratch.probe();

        assertFalse(probe.proceeded);
        assertEquals(1, scratch.intents.depth());
        CommitOrder order = scratch.intents.commit(0L);
        assertTrue(order.committed());
        assertTrue(probe.deferredApplied.get());
        assertEquals(1L, scratch.payloads.appliedCount());
        assertEquals(1L, scratch.counters.count(WritePath.KERNEL_COMMIT, ThreadOrigin.MAIN,
            HolderKind.KERNEL, WriteDisposition.GRANT));
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void aFullChannelCountsTheRefusalAndDropsTheHandover() {
        Scratch scratch = new Scratch(1);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(true, false, true, true, TICK);
        Probe first = scratch.probe();
        Probe second = scratch.probe();

        assertTrue(first.enqueued());
        assertTrue(second.proceeded);
        assertEquals(1, scratch.intents.depth());
        assertEquals(1L, scratch.intents.rejectedFullCount());
        assertEquals(1, scratch.payloads.pendingCount());
        assertEquals(1L, scratch.counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER,
            HolderKind.UNREGISTERED, WriteDisposition.DENY));
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.QUEUE_CAP_EXCEEDED));
    }

    @Test
    void anInactiveGuardCountsNothingAndPassesEverything() {
        Scratch scratch = new Scratch(8);
        scratch.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        scratch.guard.refresh(false, false, false, false, TICK);
        Probe probe = scratch.probe();

        assertTrue(probe.proceeded);
        assertEquals(0L, scratch.counters.totalAttempts());
        assertEquals(0, scratch.intents.enqueuedCount());
    }

    /** Scratch objects so no test can touch the runtime of the process. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final IntentQueue intents;
        private final WriteAuthority authority;
        private final WorldWriteGuard guard;

        private Scratch(int capacity) {
            this.intents = new IntentQueue(() -> capacity, () -> true);
            this.authority = new WriteAuthority(new OwnerRegistry(), intents, ledger, () -> false,
                () -> 2);
            this.guard = new WorldWriteGuard(counters, authority, intents, payloads, ledger);
            this.intents.bindPayload(guard);
        }

        private long grants(ThreadOrigin origin, HolderKind holder) {
            return counters.count(WritePath.BLOCK_WRITE, origin, holder, WriteDisposition.GRANT);
        }

        private Probe probe() {
            return probe(ignored -> {
            });
        }

        private Probe probe(java.util.function.Consumer<Thread> before) {
            Probe probe = new Probe(guard);
            Thread thread = new Thread(probe, "worker-under-test");
            before.accept(thread);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return probe;
        }
    }

    /** One attempt driven from a thread that is not the server thread. */
    private static final class Probe implements Runnable {

        private final WorldWriteGuard guard;
        private final AtomicBoolean deferredApplied = new AtomicBoolean();
        private final AtomicInteger probe = new AtomicInteger(-1);
        private volatile boolean proceeded;

        private Probe(WorldWriteGuard guard) {
            this.guard = guard;
        }

        @Override
        public void run() {
            int verdict = guard.classifyBlockWrite(LEVEL);
            probe.set(verdict);
            if (verdict == 0) {
                proceeded = true;
                return;
            }
            proceeded = guard.admitBlockWrite(LEVEL, "world", () -> {
                deferredApplied.set(true);
                return true;
            });
        }

        private boolean enqueued() {
            return !proceeded;
        }
    }
}
