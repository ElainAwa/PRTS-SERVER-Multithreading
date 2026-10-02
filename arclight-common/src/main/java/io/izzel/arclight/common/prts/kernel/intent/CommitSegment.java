/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The commit segment: the one moment the intent channel is walked and a deferred write is applied.
 *
 * <p>The segment walks the order frozen at planning time, one step per intent, and it is the only
 * caller of {@link IntentQueue#commit}. It runs on the thread that drives the tick, at the end of the
 * tick, so a deferred write lands on the thread that owns the world. That ownership is enforced here
 * rather than assumed: the segment carries the thread the kernel bound to it, and any other thread
 * that reaches {@link #run(long)} is refused with a code and a count instead of draining the
 * channel.</p>
 *
 * <p>Its switch is read at every tick. While the switch is off nothing is consumed and an intent
 * stays where it was frozen: an operator who routes writes before executing them sees the depth grow
 * instead of seeing a write disappear, and turning the switch on applies what is waiting.</p>
 *
 * <p>Every world has its own cursor and its own shard. A refusal inside one shard ends the walk for
 * that tick and leaves the head where it is, so the order is never repaired by skipping a step; the
 * other worlds keep their own cursors and are walked by their own position. One walk spends at most
 * the commit budget, which is a setting of its own and not the depth limit of the channel: the depth
 * says how much may wait, the budget says how much one tick may do. A walk that stops on the budget
 * states that in its readings instead of leaving it to be guessed.</p>
 */
public final class CommitSegment {

    /** What one tick's walk did. */
    public record Pass(boolean ran, int steps, int released, long commitSeq, RejectCode code) {

        /** @return a pass the switch kept from walking */
        public static Pass held() {
            return new Pass(false, 0, 0, 0L, null);
        }

        /** @return a pass a refusal ended early */
        public static Pass refused(int steps, int released, RejectCode code) {
            return new Pass(true, steps, released, 0L, code);
        }

        /** @return a pass the calling thread was not allowed to make */
        public static Pass foreign(RejectCode code) {
            return new Pass(false, 0, 0, 0L, code);
        }

        /** @return a pass that walked what it reached */
        public static Pass walked(int steps, long commitSeq) {
            return new Pass(true, steps, 0, commitSeq, null);
        }
    }

    private final IntentQueue queue;
    private final BooleanSupplier enabled;
    private final IntSupplier budget;
    private final Map<String, Long> cursors = new LinkedHashMap<>();
    private volatile Thread ownerThread;
    private volatile long cursor;
    private volatile long passes;
    private volatile long steps;
    private volatile long refusals;
    private volatile long released;
    private volatile long foreignRuns;
    private volatile long ownerConflicts;
    private volatile long budgetStops;
    private volatile int lastBudget;
    private volatile int lastSteps;
    private volatile int lastPending;
    private volatile boolean lastTruncated;

    /**
     * Creates the segment.
     *
     * @param queue   the channel it walks
     * @param enabled the switch, read at every tick so a reload applies without a restart
     * @param budget  how many intents one tick may reach, read at every walk
     */
    public CommitSegment(IntentQueue queue, BooleanSupplier enabled, IntSupplier budget) {
        this.queue = queue;
        this.enabled = enabled;
        this.budget = budget;
    }

    /**
     * Names the thread the segment may walk on.
     *
     * <p>The first call binds the thread; a later call from another thread is counted and never
     * takes the ownership away, so a worker that reaches the segment early cannot claim it.</p>
     *
     * @param thread the thread the kernel drives the tick on
     */
    public void bindOwnerThread(Thread thread) {
        Thread current = ownerThread;
        if (current == null) {
            ownerThread = thread;
            return;
        }
        if (current != thread) {
            ownerConflicts++;
        }
    }

    /** @return whether the segment has an owner thread */
    public boolean ownerBound() {
        return ownerThread != null;
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
        Thread owner = ownerThread;
        if (owner == null || owner != Thread.currentThread()) {
            foreignRuns++;
            return Pass.foreign(RejectCode.WRITE_DENIED_NOT_OWNER);
        }
        passes++;
        int limit = Math.max(1, budget.getAsInt());
        lastBudget = limit;
        lastTruncated = false;
        int applied = 0;
        int touched = 0;
        int releasedHere = 0;
        RejectCode refusal = null;
        RejectCode releasedCode = null;
        boolean stopped = false;
        for (String world : queue.worlds()) {
            long position = cursors.getOrDefault(world, 0L);
            while (queue.depth(world) > 0) {
                if (touched >= limit) {
                    budgetStops++;
                    lastTruncated = true;
                    stopped = true;
                    break;
                }
                touched++;
                CommitOrder order = queue.commit(world, position, tickIndex);
                if (order.released()) {
                    // The channel consumed the position with its code, so the walk moves on.
                    position++;
                    cursor++;
                    cursors.put(world, position);
                    released++;
                    releasedHere++;
                    releasedCode = order.code();
                    continue;
                }
                if (!order.committed()) {
                    // Nothing was consumed: the head stays and so does the expected order.
                    refusal = order.code();
                    stopped = true;
                    break;
                }
                position++;
                cursor++;
                cursors.put(world, position);
                applied++;
            }
            if (stopped) {
                break;
            }
        }
        steps += applied;
        lastSteps = applied;
        lastPending = queue.depth();
        if (refusal != null) {
            refusals++;
            return Pass.refused(applied, releasedHere, refusal);
        }
        if (releasedHere > 0) {
            return Pass.refused(applied, releasedHere, releasedCode);
        }
        return Pass.walked(applied, queue.committedCount());
    }

    /** @return the mode the next walk runs in: {@code execute} while the switch is on, else {@code hold} */
    public String mode() {
        return enabled.getAsBoolean() ? "execute" : "hold";
    }

    /** @return the orders the next walk expects, summed over the worlds */
    public long cursor() {
        return cursor;
    }

    /**
     * Returns the order one world's walk expects next.
     *
     * @param worldKey world to read
     * @return the position of that world, zero when it was never walked
     */
    public synchronized long cursor(String worldKey) {
        return cursors.getOrDefault(worldKey, 0L);
    }

    /** @return the worlds that carry a walk position, in the order they were first walked */
    public synchronized List<String> walkedWorlds() {
        return List.copyOf(cursors.keySet());
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

    /** @return intents the walk released without applying them */
    public long released() {
        return released;
    }

    /** @return walks refused because the calling thread was not the owner */
    public long foreignRuns() {
        return foreignRuns;
    }

    /** @return attempts to bind a second owner thread; must stay zero */
    public long ownerConflicts() {
        return ownerConflicts;
    }

    /** @return walks that stopped because the commit budget was spent */
    public long budgetStops() {
        return budgetStops;
    }

    /** @return the budget the last walk read */
    public int lastBudget() {
        return lastBudget;
    }

    /** @return intents the last walk applied */
    public int lastSteps() {
        return lastSteps;
    }

    /** @return intents still waiting after the last walk */
    public int lastPending() {
        return lastPending;
    }

    /** @return whether the last walk stopped on the budget with work left */
    public boolean lastTruncated() {
        return lastTruncated;
    }

    /**
     * Clears live counters without rewinding the walk positions owned by the queue lifecycle.
     * Used by the readout reset and by tests, never by the scheduler.
     */
    public void reset() {
        passes = 0L;
        steps = 0L;
        refusals = 0L;
        released = 0L;
        budgetStops = 0L;
        lastBudget = 0;
        lastSteps = 0;
        lastPending = 0;
        lastTruncated = false;
    }
}
