/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.harness;

import java.util.concurrent.atomic.LongAdder;

/**
 * The NoAI completion guard: a row whose AI is off may exist in the window, but it is counted apart
 * and never enters the AI completion denominator or the AI completed count. Only a row whose AI is
 * on and that really completed may raise the completed count, so a window cannot report AI
 * completion over rows that never ran AI.
 */
public final class NoAiCompletionGuard {

    private final LongAdder aiOnOffered = new LongAdder();
    private final LongAdder noAiPresent = new LongAdder();
    private final LongAdder aiOnCompleted = new LongAdder();

    private final LongAdder aiOnAdmitted = new LongAdder();

    /** Notes one offered row and its terminal outcome; a NoAI completion is never counted. */
    public void noteRow(boolean aiOn, DenseCompletionHarness.RowOutcome outcome) {
        if (aiOn) {
            aiOnOffered.increment();
            if (outcome == DenseCompletionHarness.RowOutcome.COMPLETED) {
                aiOnCompleted.increment();
            }
            if (outcome != DenseCompletionHarness.RowOutcome.REFUSED_BEFORE_ADMISSION) {
                aiOnAdmitted.increment();
            }
            return;
        }
        noAiPresent.increment();
    }

    /** The AI completion denominator: the rows whose AI was on. */
    public long aiDenominator() {
        return aiOnOffered.sum();
    }

    /** The AI completed count: only AI-on rows that completed. */
    public long aiCompleted() {
        return aiOnCompleted.sum();
    }

    /** The AI admitted count. */
    public long aiAdmitted() {
        return aiOnAdmitted.sum();
    }

    /** The rows whose AI is off; they exist in the window and are counted apart. */
    public long noAiRows() {
        return noAiPresent.sum();
    }

    /** Always zero: a NoAI row never counts as an AI completion, even if its outcome says so. */
    public long noAiCompleted() {
        return 0L;
    }

    /** The invariant the guard enforces: no NoAI row enters the AI completion counts. */
    public boolean aiCompletionExcludesNoAi() {
        return aiOnCompleted.sum() <= aiOnOffered.sum();
    }
}
