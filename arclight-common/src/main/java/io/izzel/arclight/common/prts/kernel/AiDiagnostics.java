/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import java.util.concurrent.atomic.LongAdder;

/** Internal AI slice diagnostics; disabled unless the private diagnostic property is set. These
 * counters are evidence only and are not formal readout keys. */
final class AiDiagnostics {

    static final String PROPERTY = "arclight.prts.aiDiag";
    static final LongAdder INSTALL_ATTEMPT = new LongAdder();
    static final LongAdder INSTALL_SUCCESS = new LongAdder();
    static final LongAdder FREEZE_HOOK_CALLS = new LongAdder();
    static final LongAdder AI_NODES = new LongAdder();
    static final LongAdder COMMIT_ATTEMPT = new LongAdder();
    static final LongAdder COMMIT_SUCCESS = new LongAdder();
    static final LongAdder COMMIT_FALLBACK = new LongAdder();
    static final LongAdder HOOK_CANDIDATES = new LongAdder();
    static final LongAdder SKIPPED_NO_TARGET = new LongAdder();
    static final LongAdder CANDIDATE_SKIP = new LongAdder();
    static final LongAdder CANDIDATE_ACCEPT = new LongAdder();
    private static final AtomicInteger CANDIDATE_SKIP_LOGS = new AtomicInteger();
    private static final AtomicInteger COMMIT_BRANCH_LOGS = new AtomicInteger();
    private static final AtomicBoolean SKIP_TICK_PLAN_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SKIP_NO_PRODUCER_LOGGED = new AtomicBoolean();

    private AiDiagnostics() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    static void installAttempt() { add(INSTALL_ATTEMPT, "install_attempt"); }
    static void installSuccess() { add(INSTALL_SUCCESS, "install_success"); }
    static void freezeHookCall() { add(FREEZE_HOOK_CALLS, "freeze_hook_calls"); }
    static void aiNodes(int count) { add(AI_NODES, count, "ai_nodes"); }
    static void commitAttempt() { add(COMMIT_ATTEMPT, "commit_attempt"); }
    static void commitSuccess() { add(COMMIT_SUCCESS, "commit_success"); }
    static void commitFallback() { add(COMMIT_FALLBACK, "commit_fallback"); }
    static void hookCandidate() { add(HOOK_CANDIDATES, "hook_candidates"); }
    static void skippedNoTarget() { add(SKIPPED_NO_TARGET, "skipped_no_target"); }

    static void candidateSkip(String reason, String actorType) {
        CANDIDATE_SKIP.increment();
        SKIPPED_NO_TARGET.increment();
        if (enabled() && CANDIDATE_SKIP_LOGS.incrementAndGet() <= 20) {
            System.out.println("[PRTS] ai-diag candidate_skip reason=" + reason
                + " actor=" + actorType + " total=" + CANDIDATE_SKIP.sum());
        }
    }

    static void commitBranch(String reason) {
        if (!enabled()) return;
        if (COMMIT_BRANCH_LOGS.incrementAndGet() <= 20) {
            System.out.println("[PRTS] ai-diag commit_branch reason=" + reason);
        }
    }

    static void candidateAccept(String reason, String actorType) {
        CANDIDATE_ACCEPT.increment();
        if (enabled()) {
            System.out.println("[PRTS] ai-diag candidate_accept reason=" + reason
                + " actor=" + actorType + " total=" + CANDIDATE_ACCEPT.sum());
        }
    }

    static void freezeSkipped(String reason) {
        if (!enabled()) return;
        if ("tick_plan_off".equals(reason) && SKIP_TICK_PLAN_LOGGED.compareAndSet(false, true)) {
            System.out.println("[PRTS] ai-diag freeze_skipped reason=tick_plan_off tick_plan=false");
        } else if ("producer_null".equals(reason)
            && SKIP_NO_PRODUCER_LOGGED.compareAndSet(false, true)) {
            System.out.println("[PRTS] ai-diag freeze_skipped reason=producer_null");
        }
    }

    private static void add(LongAdder adder, String key) { add(adder, 1L, key); }

    private static void add(LongAdder adder, long count, String key) {
        adder.add(count);
        if (enabled()) {
            System.out.println("[PRTS] ai-diag " + key + "=" + adder.sum());
        }
    }

    static void reset() {
        INSTALL_ATTEMPT.reset(); INSTALL_SUCCESS.reset(); FREEZE_HOOK_CALLS.reset();
        AI_NODES.reset(); COMMIT_ATTEMPT.reset(); COMMIT_SUCCESS.reset();
        COMMIT_FALLBACK.reset(); HOOK_CANDIDATES.reset(); SKIPPED_NO_TARGET.reset();
        CANDIDATE_SKIP.reset(); CANDIDATE_ACCEPT.reset(); CANDIDATE_SKIP_LOGS.set(0);
        COMMIT_BRANCH_LOGS.set(0);
        SKIP_TICK_PLAN_LOGGED.set(false); SKIP_NO_PRODUCER_LOGGED.set(false);
    }
}
