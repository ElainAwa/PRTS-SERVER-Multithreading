/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world a deferred write targets, and the thread that may apply it.
 *
 * <p>A routed write is frozen together with the generation of the world it was built for. When the
 * commit segment reaches it, the generation is compared again: a world that is gone, or that came
 * back as a new one, is refused with a lifecycle code and the write is dropped instead of being
 * applied to an object that no longer is the world it names. Nothing is refused while no world set was
 * ever observed, which is what keeps a readout-only run free of invented refusals.</p>
 */
class WorldLifecycleCommitTest {

    private static final long TICK = 100L;
    private static final Object LEVEL = new Object();

    @Test
    void aWorldThatDisappearedRefusesTheWriteItWasFrozenFor() {
        Scratch scratch = new Scratch();
        scratch.guard.noteLiveWorlds(List.of("world", "other"));
        scratch.guard.refresh(true, false, true, TICK);
        assertFalse(scratch.route(() -> true));
        assertEquals(1, scratch.payloads.pendingCount());

        scratch.guard.noteLiveWorlds(List.of("other"));
        CommitSegment.Pass pass = scratch.segment.run(TICK);

        assertEquals(RejectCode.WORLD_LIFECYCLE_DENIED, pass.code());
        assertEquals(1L, scratch.guard.staleWorldRefusals());
        assertEquals(1L, scratch.payloads.abandonedCount());
        assertEquals(0, scratch.payloads.pendingCount());
        assertEquals(0, scratch.intents.depth());
        assertEquals(0L, scratch.payloads.appliedCount());
        assertEquals(1L, scratch.ledger.codeCount(RejectCode.WORLD_LIFECYCLE_DENIED));
    }

    @Test
    void aWorldThatCameBackAsANewOneRefusesTheOldWrite() {
        Scratch scratch = new Scratch();
        scratch.guard.noteLiveWorlds(List.of("world"));
        long first = scratch.guard.worldEpochs().epochOf("world");
        scratch.guard.refresh(true, false, true, TICK);
        scratch.route(() -> true);

        scratch.guard.noteLiveWorlds(List.of("other"));
        scratch.guard.noteLiveWorlds(List.of("world"));
        long second = scratch.guard.worldEpochs().epochOf("world");

        assertTrue(second > first, "a world that came back is a new generation");
        assertEquals(RejectCode.WORLD_LIFECYCLE_DENIED, scratch.segment.run(TICK).code());
        assertEquals(1L, scratch.guard.staleWorldRefusals());
    }

    @Test
    void aWriteOfAWorldThatIsStillThereLands() {
        Scratch scratch = new Scratch();
        scratch.guard.noteLiveWorlds(List.of("world"));
        scratch.guard.refresh(true, false, true, TICK);
        scratch.route(() -> true);

        assertEquals(1, scratch.segment.run(TICK).steps());
        assertEquals(1L, scratch.payloads.appliedCount());
        assertEquals(0L, scratch.guard.staleWorldRefusals());
    }

    @Test
    void withoutAnyWorldSetNoLifecycleRefusalIsInvented() {
        Scratch scratch = new Scratch();
        scratch.guard.refresh(true, false, true, TICK);
        scratch.route(() -> true);

        assertEquals(1, scratch.segment.run(TICK).steps());
        assertEquals(0L, scratch.guard.staleWorldRefusals());
        assertFalse(scratch.guard.worldEpochs().tracking());
    }

    @Test
    void anEmptyWorldListIsNoInformationRatherThanEveryWorldGone() {
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("world"));
        long epoch = epochs.epochOf("world");

        epochs.observe(List.of());

        assertEquals(epoch, epochs.epochOf("world"));
        assertTrue(epoch > 0L);
        assertEquals(WorldEpochs.UNLIVE, epochs.epochOf("never-seen"));
        assertEquals(WorldEpochs.UNTRACKED, new WorldEpochs().epochOf("world"));
    }

    @Test
    void aCommitFromAThreadThatIsNotTheOwnerIsRefusedAndTheWriteIsNotPerformed() {
        Scratch scratch = new Scratch();
        scratch.guard.noteLiveWorlds(List.of("world"));
        scratch.guard.refresh(true, false, true, TICK);
        scratch.route(() -> true);
        WriteIntent intent = scratch.head();

        IntentPayload.Outcome[] outcome = new IntentPayload.Outcome[1];
        Thread worker = new Thread(() -> outcome[0] = scratch.guard.apply(intent),
            "foreign-commit-worker");
        worker.start();
        join(worker);

        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, outcome[0].code());
        assertFalse(outcome[0].finalRefusal(),
            "a wrong thread is not a reason to release the write the intent carries");
        assertEquals(1L, scratch.guard.foreignCommits());
        assertEquals(0L, scratch.payloads.appliedCount());
        assertEquals(1, scratch.payloads.pendingCount());
        assertEquals(1, scratch.intents.depth());
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** The guard, its channel and the commit segment, all on scratch objects. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final CommitSegment segment = new CommitSegment(intents, () -> true, () -> 8);
        private final WorldWriteGuard guard;

        private Scratch() {
            WriteAuthority authority = new WriteAuthority(new OwnerRegistry(), intents, ledger,
                () -> false, () -> 2);
            this.guard = new WorldWriteGuard(counters, authority, intents, payloads, ledger);
            intents.bindPayload(guard);
            segment.bindOwnerThread(Thread.currentThread());
            guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        }

        private boolean route(io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps.DeferredWrite
                                  deferred) {
            boolean[] proceeded = new boolean[1];
            Thread worker = new Thread(() -> proceeded[0] = guard.admitBlockWrite(LEVEL, "world",
                deferred), "lifecycle-worker");
            worker.start();
            join(worker);
            return proceeded[0];
        }

        private WriteIntent head() {
            return WriteIntent.draft(1L, "world", "world", "block_write", 0L, 0L, "handle",
                "xdomain", "site:a");
        }
    }
}
