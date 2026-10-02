/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accounting closure under a writer that is between its two updates.
 *
 * <p>The closure is checked every tick while writers keep writing. An attempt that has been counted and
 * not yet judged is in flight, not missing: the check subtracts it, so a transient window cannot be
 * recorded as a permanent accounting failure. An attempt that will never be judged is a different
 * thing, and the check still sees that one.</p>
 */
class WriteLedgerInFlightTest {

    private static final long TICK = 1L;

    @Test
    void anAttemptBetweenItsTwoUpdatesDoesNotBreakTheClosure() {
        WriteLedger ledger = new WriteLedger();
        WriteAttempt attempt = attempt(1L);

        ledger.noteAttempt(attempt);

        assertEquals(1L, ledger.inFlightAttempts());
        assertTrue(ledger.verifyClosure(), "an attempt in flight is not a missing counter");
        assertEquals(0L, ledger.accountingFailures());

        ledger.noteVerdict(attempt, WriteVerdict.grant("counted and judged"));

        assertEquals(0L, ledger.inFlightAttempts());
        assertTrue(ledger.verifyClosure());
        assertEquals(0L, ledger.accountingFailures());
    }

    @Test
    void anAttemptThatWillNeverBeJudgedIsStillReported() {
        WriteLedger ledger = new WriteLedger();
        WriteAttempt attempt = attempt(2L);

        ledger.noteAttempt(attempt);
        ledger.noteUnjudged(attempt);

        assertFalse(ledger.verifyClosure(), "a lost verdict is a missing counter");
        assertEquals(1L, ledger.accountingFailures());
        assertEquals(1L, ledger.codeCount(io.izzel.arclight.common.prts.kernel.codes.RejectCode
            .COUNTER_MISSING));
    }

    @Test
    void aConcurrentWriterNeverBecomesAPermanentFailure() throws Exception {
        WriteLedger ledger = new WriteLedger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch go = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                go.await();
                for (int index = 0; index < 2000; index++) {
                    WriteAttempt attempt = attempt(100L + index);
                    ledger.noteAttempt(attempt);
                    Thread.onSpinWait();
                    ledger.noteVerdict(attempt, WriteVerdict.grant("judged"));
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                stop.set(true);
            }
        }, "ledger-writer");
        Thread checker = new Thread(() -> {
            try {
                go.await();
                while (!stop.get()) {
                    ledger.verifyClosure();
                }
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "ledger-checker");
        writer.start();
        checker.start();
        go.countDown();
        writer.join(TimeUnit.SECONDS.toMillis(30));
        checker.join(TimeUnit.SECONDS.toMillis(30));

        assertNull(failure.get());
        assertEquals(0L, ledger.accountingFailures(),
            "a writer between its two updates was recorded as a permanent failure");
        assertEquals(0L, ledger.inFlightAttempts());
        assertTrue(ledger.verifyClosure());
    }

    private static WriteAttempt attempt(long id) {
        return WriteAttempt.builder(id, "world", "site:a")
            .holder(HolderKind.REGISTERED, "site:a", "thread-a")
            .target(WriteLevel.REGION, "region-1")
            .domains(java.util.Set.of("region-1"), java.util.Set.of("region-1"))
            .version(7L, TICK, 0L)
            .admitted(true)
            .build();
    }
}
