/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The commit segment: the one moment the intent channel is walked and a deferred write is applied.
 *
 * <p>The segment walks the order frozen at planning time, one step per intent, and it is the only
 * caller of {@link IntentQueue#commit}. It runs on the thread that drives the tick, at the end of
 * the tick, so a deferred write lands on the thread that owns the world.</p>
 *
 * <p>Its switch is read at every tick. While the switch is off nothing is consumed and an intent
 * stays where it was frozen: an operator who routes writes before executing them sees the depth
 * grow instead of seeing a write disappear, and turning the switch on applies what is waiting.</p>
 *
 * <p>The cursor is the order the segment expects next. Because the channel freezes the order under
 * the lock that accepts the intent, the cursor meets no gap: what was refused at the depth limit
 * never entered the channel, and what is in the channel is contiguous. A refusal ends the walk for
 * that tick and leaves the head where it is - the order is never repaired by skipping a step.</p>
 */
public final class CommitSegment {

    /** What one tick's walk did. */
    public record Pass(boolean ran, int steps, long commitSeq, RejectCode code) {

        /** @return a pass the switch kept from walking */
        public static Pass held() {
            return new Pass(false, 0, 0L, null);
        }

        /** @return a pass a refusal ended early */
        public static Pass refused(int steps, RejectCode code) {
            return new Pass(true, steps, 0L, code);
        }

        /** @return a pass that walked what it reached */
        public static Pass walked(int steps, long commitSeq) {
            return new Pass(true, steps, commitSeq, null);
        }
    }

    private final IntentQueue queue;
    private final BooleanSupplier enabled;
    private final IntSupplier budget;
    private volatile long cursor;
    private volatile long passes;
    private volatile long steps;
    private volatile long refusals;

    /**
     * Creates the segment.
     *
     * @param queue   the channel it walks
     * @param enabled the switch, read at every tick so a reload applies without a restart
     * @param budget  how many intents one tick may apply, read at every walk
     */
    public CommitSegment(IntentQueue queue, BooleanSupplier enabled, IntSupplier budget) {
        this.queue = queue;
        this.enabled = enabled;
        this.budget = budget;
    }

    /**
     * Walks the segment once, at the end of a tick.
     *
     * @param tickIndex tick the walk belongs to
     * @return what the walk did
     */
    public Pass run(long tickIndex) {
        if (!enabled.getAsBoolean()) {
            return Pass.held();
        }
        passes++;
        int limit = Math.max(1, budget.getAsInt());
        int walked = 0;
        RejectCode refusal = null;
        for (int index = 0; index < limit; index++) {
            if (queue.depth() == 0) {
                // an empty channel has nothing to walk; a commit of nothing is not a step
                break;
            }
            CommitOrder order = queue.commit(cursor, tickIndex);
            if (!order.committed()) {
                refusal = order.code();
                break;
            }
            cursor++;
            walked++;
        }
        steps += walked;
        if (refusal != null) {
            refusals++;
            return Pass.refused(walked, refusal);
        }
        return Pass.walked(walked, queue.committedCount());
    }

    /** @return the mode the next walk runs in: {@code execute} while the switch is on, else {@code hold} */
    public String mode() {
        return enabled.getAsBoolean() ? "execute" : "hold";
    }

    /** @return the order the next walk expects */
    public long cursor() {
        return cursor;
    }

    /** @return ticks the segment walked */
    public long passes() {
        return passes;
    }

    /** @return intents the walk applied */
    public long steps() {
        return steps;
    }

    /** @return walks a refusal ended early */
    public long refusals() {
        return refusals;
    }

    /** Clears the live counters. Used by the readout reset and by tests, never by the scheduler. */
    public void reset() {
        cursor = 0L;
        passes = 0L;
        steps = 0L;
        refusals = 0L;
    }
}
