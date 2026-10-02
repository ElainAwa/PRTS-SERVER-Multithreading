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
 * it exactly once; a write whose action reports that it did not land, or throws, remains pending and
 * its refusal is marked retryable, so the channel may offer it again until the retry budget is spent.
 * A handle nobody registered is a final refusal - there is nothing left to retry - and the channel
 * releases the intent instead of keeping a head that can never land. A handle the channel released is
 * forgotten here as well, so the store does not keep a write for an intent nobody will ever reach
 * again.</p>
 */
public final class IntentPayloadDirectory implements IntentPayload {

    private final Map<String, PrtsWorldWriteTaps.DeferredWrite> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1L);
    private final LongAdder applied = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder threw = new LongAdder();
    private final LongAdder unbound = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder abandoned = new LongAdder();

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

    /** @return deferred writes forgotten because the channel released their intent */
    public long abandonedCount() {
        return abandoned.sum();
    }

    /** @return deferred writes still waiting for their commit */
    public int pendingCount() {
        return pending.size();
    }
}
