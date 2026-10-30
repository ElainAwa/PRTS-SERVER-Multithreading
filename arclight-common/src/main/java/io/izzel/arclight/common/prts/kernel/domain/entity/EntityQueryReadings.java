/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.concurrent.atomic.LongAdder;

/** The two ACC-09 counters: real neighbour-query calls and the current snapshot row count. The
 * snapshot count is replaced every tick, never added to; the query count is cumulative. */
public final class EntityQueryReadings {

    private static final LongAdder QUERY_CALLS = new LongAdder();
    private static volatile long entityCount;

    private EntityQueryReadings() {
    }

    public static void noteQuery() {
        QUERY_CALLS.increment();
    }

    public static void noteEntityCount(long count) {
        entityCount = Math.max(0L, count);
    }

    public static long queryCalls() {
        return QUERY_CALLS.sum();
    }

    public static long entityCount() {
        return entityCount;
    }

    public static void reset() {
        QUERY_CALLS.reset();
        entityCount = 0L;
    }
}
