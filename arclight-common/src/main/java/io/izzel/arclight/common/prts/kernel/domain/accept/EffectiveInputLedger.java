/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.accept;

import java.util.LinkedHashMap;
import java.util.Map;

/** The mutually exclusive effective-input account of one fixed cohort: pending + done + native
 * causal cancellation + rejected before effect + partial/fault is the denominator, and the work that
 * was refused or kept in flight stays inside it; stock and cumulative columns and the six facts are
 * separate. */
public final class EffectiveInputLedger {

    /** The mutually exclusive terminal/bucket counts. */
    public record Counts(long pending, long done, long cancelledByNative, long rejectedBeforeEffect,
                         long partialOrFault) {

        public long total() {
            return pending + done + cancelledByNative + rejectedBeforeEffect + partialOrFault;
        }
    }

    /** The six completion facts, each cumulative and independent. */
    public enum Fact {
        OBSERVED,
        DISPATCHED,
        COMPUTED,
        WORLD_APPLIED,
        PUBLISHED,
        DURABLE
    }

    private Counts counts = new Counts(0L, 0L, 0L, 0L, 0L);
    private long capacityRefused;
    private long unsupported;
    private long inFlight;
    private long hardToCompute;
    private final Map<Fact, Long> facts = new LinkedHashMap<>();

    public EffectiveInputLedger() {
        for (Fact fact : Fact.values()) {
            facts.put(fact, 0L);
        }
    }

    /** Replaces the stock counts of one fixed cohort. */
    public synchronized void counts(Counts next) {
        counts = next == null ? new Counts(0L, 0L, 0L, 0L, 0L) : next;
    }

    /** Adds work that capacity refused; it stays inside the denominator. */
    public synchronized void capacityRefused(long amount) {
        capacityRefused += Math.max(0L, amount);
    }

    public synchronized void unsupported(long amount) {
        unsupported += Math.max(0L, amount);
    }

    public synchronized void inFlight(long amount) {
        inFlight += Math.max(0L, amount);
    }

    public synchronized void hardToCompute(long amount) {
        hardToCompute += Math.max(0L, amount);
    }

    /** Counts one completion fact; facts are cumulative and never replace one another. */
    public synchronized void fact(Fact fact) {
        facts.merge(fact, 1L, Long::sum);
    }

    /** The current mutually exclusive stock counts. */
    public synchronized Counts clientCounts() {
        return counts;
    }

    /** The denominator: every valid input plus the work that was not moved out of it. */
    public synchronized long denominator() {
        return counts.total() + capacityRefused + unsupported + inFlight + hardToCompute;
    }

    /** The stock side: pending and in-flight remain resident. */
    public synchronized long stock() {
        return counts.pending() + inFlight;
    }

    /** The cumulative completion side. */
    public synchronized long cumulative(Fact fact) {
        return facts.getOrDefault(fact, 0L);
    }

    /** Whether the six facts stay within the denominator and never exceed cumulative inputs. */
    public synchronized boolean consistent() {
        long denominator = denominator();
        for (Fact fact : Fact.values()) {
            if (cumulative(fact) > denominator) {
                return false;
            }
        }
        return true;
    }
}
