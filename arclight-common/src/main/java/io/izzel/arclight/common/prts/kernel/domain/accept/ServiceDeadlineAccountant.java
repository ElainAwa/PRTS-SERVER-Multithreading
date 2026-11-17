/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.accept;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The end-to-end deadline accountant: every service kind pins its first valid arrival and derives
 * its deadline from it; batching, retry and a tick continuation keep that first arrival instead of
 * resetting the clock, a waiter inherits the earlier of its own deadline and its holder's, and an
 * expired unit is counted as a deadline miss with a bounded expiry. */
public final class ServiceDeadlineAccountant {

    /** The service kinds and their declared deadlines. */
    public enum ServiceKind {
        HOT(50L),
        COLD_100(100L),
        COLD_1000(1000L),
        COLD_3000(3000L),
        COLD_10000(10000L),
        COLD_30000(30000L);

        private final long limitNanos;

        ServiceKind(long limitNanos) {
            this.limitNanos = limitNanos;
        }

        public long limitNanos() {
            return limitNanos;
        }
    }

    /** One pinned first arrival. */
    public record Entry(String unitId, ServiceKind kind, long firstArrivalNanos,
                        long inheritedFromNanos) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long misses;
    private long expired;

    /** Pins the first arrival of one unit; a repeated arrival never resets it. */
    public synchronized Entry firstArrival(String unitId, ServiceKind kind, long arrivalNanos) {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(kind, "kind");
        return entries.computeIfAbsent(unitId, key -> new Entry(unitId, kind, arrivalNanos,
            Long.MIN_VALUE));
    }

    /** A batch or a retry keeps the pinned first arrival and its deadline. */
    public synchronized long batchOrRetry(String unitId, long nowNanos) {
        Entry entry = entries.get(unitId);
        return entry == null ? Long.MIN_VALUE : deadlineNanos(entry);
    }

    /** A tick continuation keeps the pinned first arrival and its deadline. */
    public synchronized long continuation(String unitId, long nowNanos) {
        return batchOrRetry(unitId, nowNanos);
    }

    /** The absolute deadline of one unit, or Long.MIN_VALUE when it never arrived. */
    public synchronized long deadlineNanos(String unitId) {
        Entry entry = entries.get(unitId);
        return entry == null ? Long.MIN_VALUE : deadlineNanos(entry);
    }

    /** The remaining time of one unit, bounded below at zero. */
    public synchronized long remainingNanos(String unitId, long nowNanos) {
        long deadline = deadlineNanos(unitId);
        return deadline == Long.MIN_VALUE ? Long.MIN_VALUE : Math.max(0L, deadline - nowNanos);
    }

    /** A waiter inherits the earlier of its own and its holder's absolute deadlines. */
    public synchronized long inheritedDeadline(String waiterId, String holderId) {
        long own = deadlineNanos(waiterId);
        long holder = deadlineNanos(holderId);
        if (own == Long.MIN_VALUE) {
            return holder;
        }
        return holder == Long.MIN_VALUE ? own : Math.min(own, holder);
    }

    /** Counts one expired unit once; a repeated expiry is bounded. */
    public synchronized boolean expire(String unitId, long nowNanos) {
        if (deadlineNanos(unitId) == Long.MIN_VALUE || nowNanos <= deadlineNanos(unitId)) {
            return false;
        }
        if (entries.remove(unitId) == null) {
            return false;
        }
        expired++;
        misses++;
        return true;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized long expired() {
        return expired;
    }

    private static long deadlineNanos(Entry entry) {
        return entry.firstArrivalNanos() + entry.kind().limitNanos();
    }
}
