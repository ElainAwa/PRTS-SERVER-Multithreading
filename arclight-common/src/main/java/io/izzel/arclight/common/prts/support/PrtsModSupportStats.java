/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts how often a mod interoperability patch changed a decision and reports it: the first event of
 * a counter and every thousandth behind it, so a hot patch cannot fill the log. The counters are a
 * process-wide readout, never reset, and nothing here throws or blocks.
 */
public final class PrtsModSupportStats {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-modsupport");

    private static final long REPORT_EVERY = 1000L;

    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private PrtsModSupportStats() {
    }

    /** Adds one event to a counter and reports the new value when it is due. */
    public static void count(String name) {
        count(name, 1L);
    }

    /**
     * Adds events to a counter and reports the new value when it is due; a delta of zero or less
     * changes nothing.
     */
    public static void count(String name, long delta) {
        if (delta <= 0L) {
            return;
        }
        long value = COUNTERS.computeIfAbsent(name, key -> new AtomicLong()).addAndGet(delta);
        if (value == delta || value % REPORT_EVERY < delta) {
            // the first event is always reported and later ones every thousand: the second test also
            // catches a batch that jumped over a reporting boundary
            LOGGER.info("[PRTS-modsupport] {}: {}", name, value);
        }
    }

    /** @return the value of the counter, or zero when it never fired */
    public static long read(String name) {
        AtomicLong counter = COUNTERS.get(name);
        return counter == null ? 0L : counter.get();
    }
}
