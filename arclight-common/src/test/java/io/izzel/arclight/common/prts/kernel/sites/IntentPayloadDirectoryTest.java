/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The store a handed-over write waits in: consumed exactly once, and never quietly. */
class IntentPayloadDirectoryTest {

    @Test
    void aHandedOverWriteIsAppliedOnceAndOnlyOnce() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> true);

        assertTrue(payloads.apply(intent(handle)).applied());
        assertEquals(1L, payloads.appliedCount());
        assertEquals(0, payloads.pendingCount());
        IntentPayload.Outcome unbound = payloads.apply(intent(handle));
        assertEquals(RejectCode.NATIVE_UNDECLARED, unbound.code());
        assertTrue(unbound.finalRefusal(), "a handle nobody registered can never be retried");
        assertEquals(1L, payloads.unboundCount());
    }

    @Test
    void aWriteThatReportsItDidNotLandIsRefusedAndCanBeRetried() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> false);

        IntentPayload.Outcome outcome = payloads.apply(intent(handle));

        assertEquals(RejectCode.VERSION_MISMATCH, outcome.code());
        assertFalse(outcome.finalRefusal(), "a write that did not land may land on the next attempt");
        assertEquals(1L, payloads.failedCount());
        assertEquals(0L, payloads.appliedCount());
        assertEquals(1, payloads.pendingCount());
    }

    @Test
    void aFailedWriteCanBeRetriedByTheSameIntent() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        AtomicInteger attempts = new AtomicInteger();
        String handle = payloads.bind("block_write", () -> attempts.incrementAndGet() > 1);

        assertEquals(RejectCode.VERSION_MISMATCH, payloads.apply(intent(handle)).code());
        assertTrue(payloads.apply(intent(handle)).applied());
        assertEquals(2, attempts.get());
        assertEquals(1L, payloads.failedCount());
        assertEquals(1L, payloads.appliedCount());
        assertEquals(0, payloads.pendingCount());
    }

    @Test
    void aWriteThatThrowsRefusesTheCommitInsteadOfEscaping() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("platform_write", () -> {
            throw new IllegalStateException("the write path failed");
        });

        IntentPayload.Outcome outcome = payloads.apply(intent(handle));

        assertEquals(RejectCode.VERSION_MISMATCH, outcome.code());
        assertFalse(outcome.finalRefusal());
        assertEquals(1L, payloads.threwCount());
        assertEquals(0L, payloads.appliedCount());
        assertEquals(1, payloads.pendingCount());
    }

    @Test
    void aReleasedIntentForgetsItsWrite() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> true);

        payloads.release(intent(handle));

        assertEquals(1L, payloads.abandonedCount());
        assertEquals(0, payloads.pendingCount());
        assertEquals(RejectCode.NATIVE_UNDECLARED, payloads.apply(intent(handle)).code());
    }

    @Test
    void aWriteDroppedBeforeItWasEnqueuedIsForgotten() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> true);

        payloads.drop(handle);

        assertEquals(1L, payloads.droppedCount());
        assertEquals(RejectCode.NATIVE_UNDECLARED, payloads.apply(intent(handle)).code());
    }

    private static WriteIntent intent(String handle) {
        return WriteIntent.draft(1L, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, handle, "xdomain", "site:a");
    }
}
