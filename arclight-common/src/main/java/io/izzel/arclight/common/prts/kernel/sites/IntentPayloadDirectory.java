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

/**
 * Holds the writes that were handed over, so the commit segment can apply them in order.
 *
 * <p>A deferred write is stored under the handle its intent carries. A successful application consumes
 * it exactly once; a write whose action reports that it did not land, or throws, remains pending so the
 * queue can retry it instead of retaining an unprocessable intent head with no payload. A handle nobody
 * registered, and a write that ultimately reports failure, are both refusals with a code - never a quiet
 * success.</p>
 */
public final class IntentPayloadDirectory implements IntentPayload {

    private final Map<String, PrtsWorldWriteTaps.DeferredWrite> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1L);
    private final LongAdder applied = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder threw = new LongAdder();
    private final LongAdder unbound = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    /**
     * Stores a deferred write and returns the handle its intent carries.
     *
     * @param prefix a readable prefix naming the write point
     * @param write  the write itself
     * @return the handle
     */
    public String bind(String prefix, PrtsWorldWriteTaps.DeferredWrite write) {
        String handle = prefix + ":" + nextHandle.getAndIncrement();
        pending.put(handle, write);
        return handle;
    }

    /**
     * Forgets a deferred write that was never enqueued.
     *
     * @param handle the handle returned by {@link #bind}
     */
    public void drop(String handle) {
        if (handle != null && pending.remove(handle) != null) {
            dropped.increment();
        }
    }

    @Override
    public synchronized RejectCode apply(WriteIntent intent) {
        PrtsWorldWriteTaps.DeferredWrite write = pending.get(intent.payloadHandle());
        if (write == null) {
            unbound.increment();
            return RejectCode.NATIVE_UNDECLARED;
        }
        try {
            if (!write.apply()) {
                failed.increment();
                return RejectCode.VERSION_MISMATCH;
            }
        } catch (Throwable thrown) {
            // Keep the payload paired with the queue head so a transient failure can be retried.
            this.threw.increment();
            return RejectCode.VERSION_MISMATCH;
        }
        pending.remove(intent.payloadHandle());
        applied.increment();
        return null;
    }

    /** @return deferred writes that were applied */
    public long appliedCount() {
        return applied.sum();
    }

    /** @return deferred writes whose action reported that it did not land */
    public long failedCount() {
        return failed.sum();
    }

    /** @return deferred writes whose action threw instead of reporting */
    public long threwCount() {
        return threw.sum();
    }

    /** @return commits whose handle had no registered write */
    public long unboundCount() {
        return unbound.sum();
    }

    /** @return deferred writes dropped before they were enqueued */
    public long droppedCount() {
        return dropped.sum();
    }

    /** @return deferred writes still waiting for their commit */
    public int pendingCount() {
        return pending.size();
    }
}
