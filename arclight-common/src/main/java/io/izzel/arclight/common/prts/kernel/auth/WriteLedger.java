/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

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
        pair(attempt.worldId(), attempt.holderSiteId()).attempts.increment();
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
        switch (verdict.disposition()) {
            case GRANT -> pair.granted.increment();
            case INTENT -> pair.intent.increment();
            case DENY -> pair.denied.increment();
            default -> throw new IllegalStateException("unknown disposition " + verdict.disposition());
        }
        if (verdict.code() != null) {
            codes.get(verdict.code()).increment();
        }
        if (attempt.holderKind() == HolderKind.UNREGISTERED
            && verdict.disposition() == WriteDisposition.GRANT) {
            unregisteredGrants.increment();
        }
    }

    /**
     * Recomputes the closure of every pair.
     *
     * @return {@code true} when every pair closes; a failure also counts a missing counter
     */
    public boolean verifyClosure() {
        boolean ok = true;
        for (Pair pair : pairs.values()) {
            checkedPairs.increment();
            if (pair.attempts() != pair.granted() + pair.intent() + pair.denied()) {
                accountingFailures.increment();
                codes.get(RejectCode.COUNTER_MISSING).increment();
                ok = false;
            }
        }
        return ok;
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
