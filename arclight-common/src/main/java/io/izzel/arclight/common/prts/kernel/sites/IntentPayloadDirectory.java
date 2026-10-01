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
 * <p>A deferred write is stored under the handle its intent carries and is consumed exactly once:
 * the handle is removed when it is applied, so a second commit of the same intent finds nothing and
 * is refused instead of repeating a write. A handle nobody registered, and a write whose action
 * reports that it did not land, are both refusals with a code - never a quiet success.</p>
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
    public RejectCode apply(WriteIntent intent) {
        PrtsWorldWriteTaps.DeferredWrite write = pending.remove(intent.payloadHandle());
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
            // a deferred write that threw did not land either, so it refuses the commit with a
            // code and a count instead of escaping into the tick that walks the channel
            this.threw.increment();
            return RejectCode.VERSION_MISMATCH;
        }
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
