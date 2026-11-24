/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.config;

import java.util.concurrent.atomic.AtomicInteger;

/** The opt-in scale path of the owner row batch: the seat capacity follows the due rows, the worker
 * pool may grow, and one body window runs several rows under one commit order. Every accessor answers
 * the historical value while the switch is off. The counters are the per-tick evidence of that path
 * only, written and read on the owner thread; no kernel reading key is added for them. */
public final class OwnerScale {

    /** The switch; off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.ownerScale";

    /** Hard bound of the seats one tick may declare on this path. */
    public static final int ROW_MAX = 8192;

    /** Bound of the worker pool on this path. */
    public static final int WORKER_MAX = 32;

    /** Bound of one body window: more bodies than workers would only wait in the pool queue. */
    public static final int WINDOW_MAX = 128;

    private static volatile long armedSeats;
    private static volatile long countersTick = Long.MIN_VALUE;
    private static final AtomicInteger REFUSED = new AtomicInteger();
    private static final AtomicInteger TRUNCATED = new AtomicInteger();

    private OwnerScale() {
    }

    /** Whether the opt-in scale path is declared for this process. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The bound a declared worker count is clamped to; the historical bound unless the path is on. */
    public static int workerCountMax() {
        return enabled() ? WORKER_MAX : KernelSettings.WORKER_COUNT_MAX;
    }

    /** The bound a declared seat count is clamped to; the historical bound unless the path is on. */
    public static int seatBound() {
        return enabled() ? ROW_MAX : KernelSettings.JOB_QUEUE_CAP_MAX;
    }

    /** The selection bound of one due-row pass: the historical guard unless the real due rows ask
     * for more. */
    public static int rowCap(int historicalCap, int dueRows) {
        if (!enabled()) {
            return historicalCap;
        }
        return (int) Math.max(historicalCap, Math.min(ROW_MAX, Math.max(historicalCap, dueRows)));
    }

    /** Records how many rows this freeze declares, so intake and planner agree on one bound. */
    public static void armSeats(int rows) {
        armedSeats = Math.max(0, rows);
    }

    /** The declaration bound of this tick: the configured bound plus the owner rows sharing it. */
    public static int seatCapacity() {
        int configured = KernelSettings.jobQueueCap();
        if (!enabled()) {
            return configured;
        }
        return (int) Math.max(configured, Math.min(ROW_MAX, configured + armedSeats));
    }

    /** The worker queue bound: the declared one, unless the wider pool needs more room. */
    public static int workerQueueCap(int configured, int workers) {
        if (!enabled()) {
            return configured;
        }
        long wanted = Math.max(configured, (long) workers * 2L);
        return (int) Math.max(1L, Math.min(KernelSettings.WORKER_QUEUE_CAP_MAX, wanted));
    }

    /** How many rows one body window may keep in flight: the pool's own width and room. */
    public static int window(int pooled, int queueCap) {
        // The in-flight window may exceed the worker count: a body that waits on a host-served read
        // must not occupy the only slot that could start the next body, so the window keeps enough
        // bodies in flight to cover that latency while the pool stays at its real size.
        int base = Math.max(1, Math.max(pooled, 64));
        return Math.max(1, Math.min(Math.min(base, Math.max(1, queueCap)), WINDOW_MAX));
    }

    /** Starts the per-tick evidence of one owner tick; a different tick drops the previous counts. */
    public static void beginTick(long tick) {
        if (countersTick == tick) {
            return;
        }
        countersTick = tick;
        REFUSED.set(0);
        TRUNCATED.set(0);
    }

    /** Counts a begin that a capacity bound refused before any effect of that row ran. */
    public static void noteRefusedCapacity() {
        REFUSED.incrementAndGet();
    }

    /** Counts one due row the selection bound left to the host. */
    public static void noteTruncated() {
        TRUNCATED.incrementAndGet();
    }

    public static int refusedCapacity() {
        return REFUSED.get();
    }

    public static int truncatedRows() {
        return TRUNCATED.get();
    }

    /** The one evidence line of a finished body window; a log line, not a kernel reading. */
    public static String evidence(int planned, int selected, int executed, int compat, int window,
                                   int begun, int parallelBodies, int parallelWaits, long pumpNanos,
                                   long beginNanos, long controlNanos, long dispatchNanos,
                                   long waitNanos, long finishNanos, long wallNanos) {
        return "[PRTS] owner-scale planned=" + planned + " selected=" + selected + " executed="
            + executed + " compat=" + compat + " window=" + window + " begun=" + begun
            + " parallelBodies=" + parallelBodies + " parallelWaits=" + parallelWaits
            + " refusedCapacity=" + refusedCapacity() + " truncatedRows=" + truncatedRows()
            + " pumpNanos=" + pumpNanos + " beginNanos=" + beginNanos
            + " controlNanos=" + controlNanos + " dispatchNanos=" + dispatchNanos
            + " waitNanos=" + waitNanos + " finishNanos=" + finishNanos + " wallNanos=" + wallNanos;
    }
}
