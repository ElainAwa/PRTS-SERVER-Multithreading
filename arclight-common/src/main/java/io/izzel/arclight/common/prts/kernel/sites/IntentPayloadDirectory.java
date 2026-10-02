/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** A deferred write is stored under the handle its intent carries. */
public final class IntentPayloadDirectory implements IntentPayload {

    private final Map<String, PrtsWorldWriteTaps.DeferredWrite> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1L);
    private final LongAdder applied = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder threw = new LongAdder();
    private final LongAdder unbound = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder abandoned = new LongAdder();

    /** Stores a deferred write and returns the handle its intent carries. */
    public String bind(String prefix, PrtsWorldWriteTaps.DeferredWrite write) {
        String handle = prefix + ":" + nextHandle.getAndIncrement();
        pending.put(handle, write);
        return handle;
    }

    /** Forgets a deferred write that was never enqueued. */
    public void drop(String handle) {
        if (handle != null && pending.remove(handle) != null) {
            dropped.increment();
        }
    }

    @Override
    public synchronized Outcome apply(WriteIntent intent) {
        PrtsWorldWriteTaps.DeferredWrite write = pending.get(intent.payloadHandle());
        if (write == null) {
            unbound.increment();
            return Outcome.rejected(RejectCode.NATIVE_UNDECLARED);
        }
        try {
            if (!write.apply()) {
                failed.increment();
                return Outcome.retryable(RejectCode.VERSION_MISMATCH);
            }
        } catch (Throwable thrown) {
            this.threw.increment();
            return Outcome.retryable(RejectCode.VERSION_MISMATCH);
        }
        pending.remove(intent.payloadHandle());
        applied.increment();
        return Outcome.APPLIED;
    }

    @Override
    public synchronized void release(WriteIntent intent) {
        if (intent != null && pending.remove(intent.payloadHandle()) != null) {
            abandoned.increment();
        }
    }

    public long appliedCount() {
        return applied.sum();
    }

    public long failedCount() {
        return failed.sum();
    }

    public long threwCount() {
        return threw.sum();
    }

    public long unboundCount() {
        return unbound.sum();
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long abandonedCount() {
        return abandoned.sum();
    }

    public int pendingCount() {
        return pending.size();
    }
}
