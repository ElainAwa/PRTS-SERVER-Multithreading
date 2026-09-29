/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts how often a mod interoperability patch actually changed a decision, and reports it.
 *
 * <p>Every patch of that layer is a switch an operator can leave off, so an operator has to be
 * able to tell whether a patch that is on is doing anything at all. A patch bumps a counter at the
 * one place where it changed the outcome, and the first event of a counter and every thousandth
 * event behind it are logged: a hot patch cannot fill the log, and the number stays readable.</p>
 *
 * <p>The counters are process wide and are never reset. They are a readout, not a state machine,
 * so nothing here throws and nothing here blocks.</p>
 */
public final class PrtsModSupportStats {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-modsupport");

    /** Number of events between two reports of the same counter. */
    private static final long REPORT_EVERY = 1000L;

    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private PrtsModSupportStats() {
    }

    /**
     * Adds one event to a counter and reports the new value when it is due.
     *
     * @param name counter name, used as it appears in the log
     */
    public static void count(String name) {
        count(name, 1L);
    }

    /**
     * Adds events to a counter and reports the new value when it is due.
     *
     * @param name  counter name, used as it appears in the log
     * @param delta number of events to add; a value of zero or less changes nothing
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

    /**
     * Reads a counter without changing it.
     *
     * @param name counter name
     * @return the value of the counter, or zero when it never fired
     */
    public static long read(String name) {
        AtomicLong counter = COUNTERS.get(name);
        return counter == null ? 0L : counter.get();
    }
}
