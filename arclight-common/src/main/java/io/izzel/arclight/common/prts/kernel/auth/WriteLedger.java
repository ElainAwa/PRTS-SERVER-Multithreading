/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt.WriteOp;

/** Counts every write attempt and every disposition, per world and site. The ledger is entered
 * before the judgement, so the accounting closure {@code attempts == granted + intent + denied} is
 * a statement about all attempts, including the ones that fail behind the decision point. */
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

        public long attempts() {
            return attempts.sum();
        }

        public long granted() {
            return granted.sum();
        }

        public long intent() {
            return intent.sum();
        }

        public long denied() {
            return denied.sum();
        }

        public long inFlight() {
            return inFlight.sum();
        }

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

    /** Counts an attempt before it is judged. */
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

    /** Counts the disposition of an attempt and its code, if it carries one. */
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

    /** Counts an attempt that will never receive a verdict. A judgement that fails before it
     * produces a verdict - an error on the way, a caller that never returned - takes the attempt
     * out of flight. */
    public void noteUnjudged(WriteAttempt attempt) {
        if (attempt.op() == WriteOp.READ) {
            return;
        }
        Pair pair = pair(attempt.worldId(), attempt.holderSiteId());
        pair.enter();
        pair.inFlight.decrement();
        pair.leave();
    }

    /** Counts one refusal code on its own. A write point that refuses an attempt before it reaches
     * a per-world pair - the commit segment, for example, whose refusal belongs to no world of its
     * own - counts the code here, so the code table stays the one place a reader has to look. */
    public void noteCode(RejectCode code) {
        LongAdder counter = codes.get(code);
        if (counter != null) {
            counter.increment();
        }
    }

    /** Recomputes the closure of every pair. An attempt that was counted and not yet judged is
     * subtracted first. */
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

    public long inFlightAttempts() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.inFlight();
        }
        return total;
    }

    public boolean accountingOk() {
        return accountingFailures.sum() == 0L;
    }

    public long checkedPairs() {
        return checkedPairs.sum();
    }

    public long accountingFailures() {
        return accountingFailures.sum();
    }

    public long readGrants() {
        return readGrants.sum();
    }

    public long unregisteredAttempts() {
        return unregisteredAttempts.sum();
    }

    public long unregisteredGrants() {
        return unregisteredGrants.sum();
    }

    public long codeCount(RejectCode code) {
        LongAdder counter = codes.get(code);
        return counter == null ? 0L : counter.sum();
    }

    public Map<String, Pair> pairs() {
        return new LinkedHashMap<>(pairs);
    }

    public long totalAttempts() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.attempts();
        }
        return total;
    }

    public long totalGranted() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.granted();
        }
        return total;
    }

    public long totalIntent() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.intent();
        }
        return total;
    }

    public long totalDenied() {
        long total = 0L;
        for (Pair pair : pairs.values()) {
            total += pair.denied();
        }
        return total;
    }

    public int pairCount() {
        return pairs.size();
    }

    private Pair pair(String worldId, String siteId) {
        return pairs.computeIfAbsent(worldId + "|" + siteId, key -> new Pair());
    }
}
