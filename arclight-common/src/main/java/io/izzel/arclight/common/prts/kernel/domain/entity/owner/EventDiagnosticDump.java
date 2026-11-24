/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic-only stdout trace of the three movement-related event kinds. The limit is read once
 * from a JVM property at class initialization; absent, invalid or zero keeps every shipped path
 * silent. It is not a reading key: nothing is published to status, commit or the native wave. */
public final class EventDiagnosticDump {

    private static final String PROPERTY = "arclight.prts.diag.eventDump";
    private static final int LIMIT = parse(System.getProperty(PROPERTY, ""));
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicInteger DUMPED = new AtomicInteger();

    private EventDiagnosticDump() {
    }

    private static int parse(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (RuntimeException invalid) {
            return 0;
        }
    }

    /** Emits one ordered trace line when the diagnostic limit allows it; a no-op by default. */
    public static void note(long tick, String entity, String kind) {
        int limit = limitForTest();
        if (limit <= 0 || entity == null || kind == null) {
            return;
        }
        int slot;
        do {
            slot = DUMPED.get();
            if (slot >= limit) {
                return;
            }
        } while (!DUMPED.compareAndSet(slot, slot + 1));
        System.out.println("[PRTS] event.dump|tick=" + tick + "|entity=" + entity
            + "|kind=" + kind + "|seq=" + SEQ.incrementAndGet());
    }

    private static int limitForTest() {
        return overrideForTest >= 0 ? overrideForTest : LIMIT;
    }

    private static volatile int overrideForTest = -1;

    /** Test-only override; production always reads the class-initialized property. */
    public static void setLimitForTest(int limit) {
        overrideForTest = limit;
    }

    /** Test-only reset of the one-shot counters. */
    public static void resetForTest() {
        overrideForTest = -1;
        DUMPED.set(0);
        SEQ.set(0);
    }

    /** Test-only observation of emitted lines. */
    public static int dumpedForTest() {
        return DUMPED.get();
    }

    /** Test-only observation of the emitted order counter. */
    public static long sequenceForTest() {
        return SEQ.get();
    }
}
