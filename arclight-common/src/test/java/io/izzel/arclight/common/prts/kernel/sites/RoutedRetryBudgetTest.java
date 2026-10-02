/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry budget of a really routed write.
 *
 * <p>The write arrives the way the running server delivers it - from a thread nobody declared, through
 * the write path seam, handed to the intent channel - and its deferred write never lands. What is
 * checked here is that the budget of the channel, and not an unbounded retry, decides when that write
 * stops: the head is refused, refused again, and then released with its code, so the write behind it
 * lands instead of waiting for the rest of the process.</p>
 */
class RoutedRetryBudgetTest {

    private static final long TICK = 100L;
    private static final Object LEVEL = new Object();

    @Test
    void aRoutedWriteThatNeverLandsIsReleasedAfterItsRetryBudgetAndFreesTheChannel() {
        IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        WriteLedger ledger = new WriteLedger();
        WriteAuthority authority = new WriteAuthority(new OwnerRegistry(), intents, ledger,
            () -> false, () -> 2);
        WorldWriteGuard guard = new WorldWriteGuard(new WritePathCounters(), authority, intents,
            payloads, ledger);
        intents.bindPayload(guard);
        CommitSegment segment = new CommitSegment(intents, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());
        guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        guard.refresh(true, false, true, TICK);

        AtomicInteger attempts = new AtomicInteger();
        assertFalse(route(guard, () -> {
            attempts.incrementAndGet();
            return false;
        }), "a routed write does not land on the write path");
        assertEquals(1, intents.depth());
        assertEquals(1, payloads.pendingCount());

        CommitSegment.Pass first = segment.run(TICK);
        CommitSegment.Pass second = segment.run(TICK + 1L);
        assertEquals(RejectCode.VERSION_MISMATCH, first.code());
        assertEquals(RejectCode.VERSION_MISMATCH, second.code());
        assertEquals(1, intents.depth(), "the head is offered again while the budget lasts");
        assertEquals(2, attempts.get());

        CommitSegment.Pass exhausted = segment.run(TICK + 2L);

        assertEquals(RejectCode.VERSION_MISMATCH, exhausted.code());
        assertEquals(1L, intents.retryExhaustedCount(), "the budget, and not the process, ended it");
        assertEquals(1L, intents.releasedCount());
        assertEquals(0, intents.depth());
        assertEquals(0, payloads.pendingCount(), "the released write is forgotten by the store");
        assertEquals(1L, payloads.abandonedCount());
        assertEquals(3, attempts.get());
        assertEquals(0L, intents.executedCount());
        assertEquals(3L, intents.payloadRefusalCount());
        assertTrue(ledger.codeCount(RejectCode.VERSION_MISMATCH) >= 3L);
    }

    @Test
    void theReleasedHeadLetsTheNextRoutedWriteLand() {
        IntentQueue intents = new IntentQueue(() -> 8, () -> 0);
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        WriteLedger ledger = new WriteLedger();
        WriteAuthority authority = new WriteAuthority(new OwnerRegistry(), intents, ledger,
            () -> false, () -> 0);
        WorldWriteGuard guard = new WorldWriteGuard(new WritePathCounters(), authority, intents,
            payloads, ledger);
        intents.bindPayload(guard);
        CommitSegment segment = new CommitSegment(intents, () -> true, () -> 8);
        segment.bindOwnerThread(Thread.currentThread());
        guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        guard.refresh(true, false, true, TICK);

        route(guard, () -> false);
        route(guard, () -> true);

        assertEquals(2, intents.depth());
        CommitSegment.Pass pass = segment.run(TICK);

        assertEquals(RejectCode.VERSION_MISMATCH, pass.code());
        assertEquals(1, pass.steps(), "the write behind the released one lands in the same walk");
        assertEquals(0, intents.depth());
        assertEquals(1L, payloads.appliedCount());
        assertEquals(1L, payloads.abandonedCount());
        assertEquals(1L, intents.executedCount());
        assertEquals(1L, intents.retryExhaustedCount(),
            "a budget of zero means the first refusal spends it");
    }

    /** Drives one undeclared write from a worker thread, the way the plugin write arrives. */
    private static boolean route(WorldWriteGuard guard, io.izzel.arclight.common.prts.support
        .PrtsWorldWriteTaps.DeferredWrite deferred) {
        boolean[] proceeded = new boolean[1];
        Thread worker = new Thread(() -> proceeded[0] = guard.admitBlockWrite(LEVEL, "world",
            deferred), "routed-write-worker");
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return proceeded[0];
    }
}
