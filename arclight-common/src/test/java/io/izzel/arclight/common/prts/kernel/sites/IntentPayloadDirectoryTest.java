/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The store a handed-over write waits in: consumed exactly once, and never quietly. */
class IntentPayloadDirectoryTest {

    @Test
    void aHandedOverWriteIsAppliedOnceAndOnlyOnce() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> true);

        assertNull(payloads.apply(intent(handle)));
        assertEquals(1L, payloads.appliedCount());
        assertEquals(0, payloads.pendingCount());
        assertEquals(RejectCode.NATIVE_UNDECLARED, payloads.apply(intent(handle)));
        assertEquals(1L, payloads.unboundCount());
    }

    @Test
    void aWriteThatReportsItDidNotLandRefusesTheCommit() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> false);

        assertEquals(RejectCode.VERSION_MISMATCH, payloads.apply(intent(handle)));
        assertEquals(1L, payloads.failedCount());
        assertEquals(0L, payloads.appliedCount());
        assertEquals(1, payloads.pendingCount());
    }

    @Test
    void aFailedWriteCanBeRetriedByTheSameIntent() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        AtomicInteger attempts = new AtomicInteger();
        String handle = payloads.bind("block_write", () -> attempts.incrementAndGet() > 1);

        assertEquals(RejectCode.VERSION_MISMATCH, payloads.apply(intent(handle)));
        assertNull(payloads.apply(intent(handle)));
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

        assertEquals(RejectCode.VERSION_MISMATCH, payloads.apply(intent(handle)));
        assertEquals(1L, payloads.threwCount());
        assertEquals(0L, payloads.appliedCount());
        assertEquals(1, payloads.pendingCount());
    }

    @Test
    void aWriteDroppedBeforeItWasEnqueuedIsForgotten() {
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String handle = payloads.bind("block_write", () -> true);

        payloads.drop(handle);

        assertEquals(1L, payloads.droppedCount());
        assertEquals(RejectCode.NATIVE_UNDECLARED, payloads.apply(intent(handle)));
    }

    private static WriteIntent intent(String handle) {
        return WriteIntent.draft(1L, "world", "world", "block_write", 0L, handle, "xdomain",
            "site:a");
    }
}
