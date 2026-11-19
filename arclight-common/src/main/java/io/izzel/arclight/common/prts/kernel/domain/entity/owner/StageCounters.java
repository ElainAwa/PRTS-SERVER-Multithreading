/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Observation counters for the stage methods the launch transforms add; off in every decision. */
public final class StageCounters {

    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private StageCounters() {
    }

    /** Counts one entry of the named stage; called from transformed bytecode. */
    public static void note(String stage) {
        COUNTS.computeIfAbsent(stage, key -> new LongAdder()).increment();
    }

    /** Adds a batch of entries to one stage counter; used where one body counts several draws. */
    public static void add(String stage, long count) {
        if (count > 0L) {
            COUNTS.computeIfAbsent(stage, key -> new LongAdder()).add(count);
        }
    }

    /** Prints one sorted line per stage; diagnostic only. */
    public static void dump() {
        COUNTS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> System.out.println("[PRTS] stage " + entry.getKey() + "="
                + entry.getValue().sum()));
    }
}