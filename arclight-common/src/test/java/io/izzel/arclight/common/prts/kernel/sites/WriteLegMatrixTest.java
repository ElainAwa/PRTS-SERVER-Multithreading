/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three switch positions the batch runs on the live instance, pinned at the decision point.
 *
 * <p>Every test drives the shape the asynchronous plugin write has in the run: an attempt from a
 * thread nobody declared, judged on the long path, with the three switches of the kernel settings
 * in the position of the leg. The default leg counts the attempt and lets the write through, the
 * enforcing leg refuses it for real with a code, and the routing leg freezes it into the channel,
 * where the commit segment applies it exactly once and in the frozen order.</p>
 *
 * <p>The counters read here are the ones the export publishes as whole-path totals, so a leg that
 * closes in these tests is a leg whose published readings cannot disagree with it.</p>
 */
class WriteLegMatrixTest {

    private static final long TICK = 100L;
    private static final Object LEVEL = new Object();

    @Test
    void theDefaultLegCountsTheAttemptAndLetsTheWriteThrough() {
        Scratch scratch = new Scratch(8, true).switches(false, false);

        Probe probe = scratch.attempt(null);

        assertTrue(probe.proceeded, "with every switch at its default the write still lands");
        assertFalse(probe.deferredApplied.get());
        assertEquals(1L, scratch.counters.totalAttempts());
        assertEquals(0L, scratch.counters.total(WriteDisposition.DENY));
        assertEquals(1L, scratch.counters.total(WriteDisposition.INTENT));
        assertEquals(1L, scratch.counters.unregisteredAttempts());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.WRITE_DENIED_NOT_OWNER));
        assertEquals(0L, scratch.intents.enqueuedCount());
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void theEnforcingLegRefusesTheWriteAndNothingLands() {
        Scratch scratch = new Scratch(8, true).switches(true, false);

        Probe probe = scratch.attempt(null);

        assertFalse(probe.proceeded, "enforcement refuses the write instead of recording it");
        assertFalse(probe.deferredApplied.get());
        assertEquals(1L, scratch.counters.total(WriteDisposition.DENY),
            "the whole-path refused total is what the export publishes as the denied reading");
        assertEquals(0L, scratch.counters.total(WriteDisposition.GRANT));
        assertEquals(1L, scratch.counters.unregisteredAttempts());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, scratch.guard.lastDecision().code());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.WRITE_DENIED_NOT_OWNER));
        assertEquals(0L, scratch.intents.enqueuedCount());
        assertEquals(0L, scratch.intents.executedCount());
        assertEquals(0, scratch.payloads.pendingCount());
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void theEnforcingRefusalCarriesOneOfTheNineWriteSideCodesAndAddsNone() {
        Set<RejectCode> writeSide = new HashSet<>();
        for (RejectTrigger trigger : RejectTrigger.values()) {
            if (trigger.scope() == RejectTrigger.Scope.WRITE && trigger.code() != null) {
                writeSide.add(trigger.code());
            }
        }
        Scratch scratch = new Scratch(8, true).switches(true, false);

        scratch.attempt(null);

        RejectCode raised = scratch.guard.lastDecision().code();
        assertTrue(writeSide.contains(raised),
            "the refusal code of the enforcing leg is one of the codes the write triggers use");
        assertEquals(9, writeSide.size());
        assertEquals(20, RejectCode.values().length, "the batch adds no rejection code");
    }

    @Test
    void theRoutingCommitLegLandsEachFrozenWriteExactlyOnceAtTheCommit() {
        Scratch scratch = new Scratch(8, true).switches(false, true);

        List<String> landed = new ArrayList<>();
        Probe first = scratch.attempt(landed);
        Probe second = scratch.attempt(landed);
        Probe third = scratch.attempt(landed);

        assertFalse(first.proceeded);
        assertFalse(second.proceeded);
        assertFalse(third.proceeded);
        assertEquals(3, scratch.intents.depth());
        assertEquals(3L, scratch.intents.enqueuedCount());
        assertEquals(0L, scratch.intents.executedCount());
        assertEquals(3, scratch.payloads.pendingCount());
        assertEquals(3L, scratch.counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER,
            HolderKind.UNREGISTERED, WriteDisposition.INTENT), "the routing path counts every handover");

        CommitSegment.Pass pass = scratch.segment.run(TICK);

        assertTrue(pass.ran());
        assertEquals(3, pass.steps());
        assertEquals(List.of("write-1", "write-2", "write-3"), landed,
            "the writes land in the order the channel froze them");
        assertEquals(3L, scratch.intents.executedCount());
        assertEquals(0L, scratch.intents.orderViolationCount());
        assertEquals(0, scratch.intents.depth());
        assertEquals(0, scratch.payloads.pendingCount());
        assertEquals(3L, scratch.payloads.appliedCount());
        assertEquals(3L, scratch.segment.cursor());
        assertEquals(TICK, scratch.intents.lastExecTick());
        assertEquals(3L, scratch.counters.count(WritePath.KERNEL_COMMIT, ThreadOrigin.MAIN,
            HolderKind.KERNEL, WriteDisposition.GRANT), "the commit path counts every applied write");

        assertEquals(0, scratch.segment.run(TICK).steps(), "a second walk finds nothing to apply");
        assertEquals(3L, scratch.payloads.appliedCount());
        assertEquals(3L, scratch.intents.executedCount());
        assertTrue(scratch.counters.closureHolds());
    }

    /** The three switches, the counters and the channel one leg reads. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final IntentQueue intents;
        private final CommitSegment segment;
        private final WriteAuthority authority;
        private final WorldWriteGuard guard;
        private final AtomicInteger writes = new AtomicInteger();

        private Scratch(int capacity, boolean segmentRuns) {
            this.intents = new IntentQueue(() -> capacity);
            this.segment = new CommitSegment(intents, () -> segmentRuns, intents::capacity);
            this.authority = new WriteAuthority(new OwnerRegistry(), intents, ledger, () -> false,
                () -> 2);
            this.guard = new WorldWriteGuard(counters, authority, intents, payloads, ledger);
            this.intents.bindPayload(guard);
            this.guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        }

        /**
         * Puts the two switches of the leg in place.
         *
         * @param enforce whether an undeclared writer is refused instead of recorded
         * @param route   whether an undeclared write is handed to the intent channel
         * @return this scratch, so a test reads as the leg it drives
         */
        private Scratch switches(boolean enforce, boolean route) {
            guard.refresh(true, enforce, route, TICK);
            return this;
        }

        /** Drives one attempt from a thread that is never the server one. */
        private Probe attempt(List<String> landed) {
            Probe probe = new Probe(this, landed);
            Thread thread = new Thread(probe, "write-leg-worker");
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

    /** One attempt from an undeclared thread, with the deferred write the routing leg hands over. */
    private static final class Probe implements Runnable {

        private final Scratch scratch;
        private final List<String> landed;
        private final AtomicBoolean deferredApplied = new AtomicBoolean();
        private volatile boolean proceeded;

        private Probe(Scratch scratch, List<String> landed) {
            this.scratch = scratch;
            this.landed = landed;
        }

        @Override
        public void run() {
            if (scratch.guard.classifyBlockWrite(LEVEL) == 0) {
                proceeded = true;
                return;
            }
            String name = landed == null ? null : "write-" + scratch.writes.incrementAndGet();
            proceeded = scratch.guard.admitBlockWrite(LEVEL, "world", () -> {
                deferredApplied.set(true);
                if (landed != null) {
                    landed.add(name);
                }
                return true;
            });
        }
    }
}
