/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/** The cpu clock one span is read against. A wall span includes the time the thread was parked,
 * blocked on a lock or an io call and the time a collection held it still; the thread cpu time does
 * not, so the two together separate "this thread worked" from "this thread was held". Unsupported
 * or disabled, every read answers -1. */
public final class PrtsCpuClock {

    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean SUPPORTED = BEAN.isCurrentThreadCpuTimeSupported();
    private static volatile boolean enabled;

    private PrtsCpuClock() {
    }

    /** Enables the per-thread cpu clock once, on the thread that installs the observation. */
    public static synchronized void enable() {
        if (!SUPPORTED || enabled) {
            return;
        }
        try {
            BEAN.setThreadCpuTimeEnabled(true);
            enabled = true;
        } catch (RuntimeException | Error ignored) {
            enabled = false;
        }
    }

    public static boolean supported() {
        return enabled;
    }

    /** @return the cpu nanoseconds of the calling thread, or -1 when it cannot say */
    public static long now() {
        if (!enabled) {
            return -1L;
        }
        long nanos = BEAN.getCurrentThreadCpuTime();
        return nanos < 0L ? -1L : nanos;
    }

    /** @return the cpu nanoseconds used since the stamp, or -1 when unknown */
    public static long since(long stampNanos) {
        if (stampNanos < 0L) {
            return -1L;
        }
        long now = now();
        return now < 0L ? -1L : now - stampNanos;
    }
}
