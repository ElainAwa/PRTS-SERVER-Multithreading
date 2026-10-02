/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

/**
 * Counts every write attempt and every disposition, per world and site.
 *
 * <p>The ledger is entered before the judgement, so the accounting closure
 * {@code attempts == granted + intent + denied} is a statement about all attempts, including the
 * ones that fail behind the decision point. A pair whose three parts do not add up counts a missing
 * counter and makes the observation face invalid.</p>
 *
 * <p>Reads are classified but kept outside the write closure: a read takes no write right and never
 * enters one of the three write dispositions.</p>
 */
public final class WriteLedger {

    /** One accounting pair: attempts and the three dispositions they ended in. */
    public static final class Pair {

        private final LongAdder attempts = new LongAdder();
        private final LongAdder granted = new LongAdder();
        private final LongAdder intent = new LongAdder();
        private final LongAdder denied = new LongAdder();
        private final LongAdder inFlight = new LongAdder();
        private final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();

        /** @return write attempts of this pair */
        public long attempts() {
            return attempts.sum();
        }

        /** @return grants of this pair */
        public long granted() {
            return granted.sum();
        }

        /** @return intents of this pair */
        public long intent() {
            return intent.sum();
        }

        /** @return refusals of this pair */
        public long denied() {
            return denied.sum();
        }

        /** @return attempts of this pair that were counted and not yet judged */
        public long inFlight() {
            return inFlight.sum();
        }

        /** @return the version of the pair, odd while a writer is inside it */
        long version() {
            return version.get();
        }

        private void enter() {
            version.incrementAndGet();
        }

        private void leave() {
            version.incrementAndGet();
        }
    }

    private final Map<String, Pair> pairs = new ConcurrentHashMap<>();
    private final Map<RejectCode, LongAdder> codes = new ConcurrentHashMap<>();
    private final LongAdder readGrants = new LongAdder();
    private final LongAdder unregisteredAttempts = new LongAdder();
    private final LongAdder unregisteredGrants = new LongAdder();
    private final LongAdder accountingFailures = new LongAdder();
    private final LongAdder checkedPairs = new LongAdder();

    public WriteLedger() {
        for (RejectCode code : RejectCode.values()) {
            codes.put(code, new LongAdder());
        }
    }

    /**
     * Counts an attempt before it is judged.
     *
     * @param attempt the attempt
     */
    public void noteAttempt(WriteAttempt attempt) {
        if (attempt.op() == WriteOp.READ) {
            readGrants.increment();
            return;
        }
        Pair pair = pair(attempt.worldId(), attempt.holderSiteId());
        // The whole update happens inside the version bracket, so a reader that sees an even version
        // on both sides of its reads saw a pair no writer was inside.
        pair.enter();
        pair.inFlight.increment();
        pair.attempts.increment();
        pair.leave();
        if (attempt.holderKind() == HolderKind.UNREGISTERED) {
            unregisteredAttempts.increment();
        }
    }

    /**
     * Counts the disposition of an attempt and its code, if it carries one.
     *
     * @param attempt the attempt
     * @param verdict the verdict it received
     */
    public void noteVerdict(WriteAttempt attempt, WriteVerdict verdict) {
        if (attempt.op() == WriteOp.READ) {
            return;
        }
        Pair pair = pair(attempt.worldId(), attempt.holderSiteId());
        pair.enter();
        switch (verdict.disposition()) {
            case GRANT -> pair.granted.increment();
            case INTENT -> pair.intent.increment();
            case DENY -> pair.denied.increment();
            default -> {
                pair.leave();
                throw new IllegalStateException("unknown disposition " + verdict.disposition());
            }
        }
        pair.inFlight.decrement();
        pair.leave();
        if (verdict.code() != null) {
            codes.get(verdict.code()).increment();
        }
        if (attempt.holderKind() == HolderKind.UNREGISTERED
            && verdict.disposition() == WriteDisposition.GRANT) {
            unregisteredGrants.increment();
        }
    }

    /**
     * Counts an attempt that will never receive a verdict.
     *
     * <p>A judgement that fails before it produces a verdict - an error on the way, a caller that
     * never returned - takes the attempt out of flight. The accounting check then sees it as a
     * missing counter, which is what it is, instead of leaving it in flight forever where the check
     * would politely subtract it for the rest of the process.</p>
     *
     * @param attempt the attempt that will not be judged
     */
    public void noteUnjudged(WriteAttempt attempt) {
        if (attempt.op() == WriteOp.READ) {
            return;
        }
        Pair pair = pair(attempt.worldId(), attempt.holderSiteId());
        pair.enter();
        pair.inFlight.decrement();
        pair.leave();
    }

    /**
     * Counts one refusal code on its own.
     *
     * <p>A write point that refuses an attempt before it reaches a per-world pair - the commit
     * segment, for example, whose refusal belongs to no world of its own - counts the code here, so
     * the code table stays the one place a reader has to look.</p>
     *
     * @param code the code to count
     */
    public void noteCode(RejectCode code) {
        LongAdder counter = codes.get(code);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * Recomputes the closure of every pair.
     *
     * <p>An attempt that was counted and not yet judged is subtracted first. A writer that is between
     * the two updates is in flight, not missing: the check reads the closure of the pairs whose
     * judgement has landed, so a walk that happens to run across a concurrent writer cannot record a
     * transient state as a permanent accounting failure.</p>
     *
     * @return {@code true} when every pair closes; a failure also counts a missing counter
     */
    public boolean verifyClosure() {
        boolean ok = true;
        for (Pair pair : pairs.values()) {
            checkedPairs.increment();
            long before = pair.version();
            if ((before & 1L) != 0L) {
                // A writer is inside this pair right now; that window is not a failure.
                continue;
            }
            long seen = pair.granted() + pair.intent() + pair.denied();
            long counted = pair.attempts();
            long inFlight = pair.inFlight();
            if (pair.version() != before) {
                // A writer entered and left while these numbers were being read; check it next time.
                continue;
            }
            // No verdict without an attempt, and every attempt that has no verdict yet is covered by
            // the in-flight cell.
            if (counted < seen || counted - seen > inFlight) {
                accountingFailures.increment();
                codes.get(RejectCode.COUNTER_MISSING).increment();
                ok = false;
            }
        }
        return ok;
    }

    /** @return attempts that were counted and not yet judged */
    public long inFlightAttempts() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.inFlight();
        }
        return total;
    }

    /** @return {@code true} when no closure has failed in this process */
    public boolean accountingOk() {
        return accountingFailures.sum() == 0L;
    }

    /** @return accounting pairs that were checked */
    public long checkedPairs() {
        return checkedPairs.sum();
    }

    /** @return closure failures; must stay zero */
    public long accountingFailures() {
        return accountingFailures.sum();
    }

    /** @return reads that passed the decision point */
    public long readGrants() {
        return readGrants.sum();
    }

    /** @return write attempts of unregistered holders */
    public long unregisteredAttempts() {
        return unregisteredAttempts.sum();
    }

    /** @return grants handed to unregistered holders; must stay zero */
    public long unregisteredGrants() {
        return unregisteredGrants.sum();
    }

    /** @return the count of one code, zero included */
    public long codeCount(RejectCode code) {
        LongAdder counter = codes.get(code);
        return counter == null ? 0L : counter.sum();
    }

    /** @return the accounting pairs, keyed by {@code world|site}, in insertion order */
    public Map<String, Pair> pairs() {
        return new LinkedHashMap<>(pairs);
    }

    /** @return write attempts over all pairs */
    public long totalAttempts() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.attempts();
        }
        return total;
    }

    /** @return grants over all pairs */
    public long totalGranted() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.granted();
        }
        return total;
    }

    /** @return intents over all pairs */
    public long totalIntent() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.intent();
        }
        return total;
    }

    /** @return refusals over all pairs */
    public long totalDenied() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.denied();
        }
        return total;
    }

    /** @return the number of accounting pairs, the site registry entry count */
    public int pairCount() {
        return pairs.size();
    }

    private Pair pair(String worldId, String siteId) {
        return pairs.computeIfAbsent(worldId + "|" + siteId, key -> new Pair());
    }
}
