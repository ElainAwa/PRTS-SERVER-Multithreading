/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.intent;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** The segment walks the order frozen at planning time, one step per intent, and it is the only
 * caller of {@link IntentQueue#commit}. */
public final class CommitSegment {

    /** What one tick's walk did. */
    public record Pass(boolean ran, int steps, int released, long commitSeq, RejectCode code) {

        public static Pass held() {
            return new Pass(false, 0, 0, 0L, null);
        }

        public static Pass refused(int steps, int released, RejectCode code) {
            return new Pass(true, steps, released, 0L, code);
        }

        public static Pass foreign(RejectCode code) {
            return new Pass(false, 0, 0, 0L, code);
        }

        public static Pass walked(int steps, long commitSeq) {
            return new Pass(true, steps, 0, commitSeq, null);
        }
    }

    /** One intent this walk applied: the world it landed in and the order the channel froze it
     * with. The intent layer publishes them; the layer above decides what to do with them. */
    public record AppliedStep(String worldId, long frozenOrder) {
    }

    private final IntentQueue queue;
    private final BooleanSupplier enabled;
    private final IntSupplier budget;
    private final Map<String, Long> cursors = new LinkedHashMap<>();
    private volatile List<AppliedStep> appliedSteps = List.of();
    private volatile Thread ownerThread;
    private volatile long cursor;
    private volatile long passes;
    private volatile long steps;
    private volatile long walkNanos;
    private volatile long refusals;
    private volatile long released;
    private volatile long foreignRuns;
    private volatile long ownerConflicts;
    private volatile long budgetStops;
    private volatile int lastBudget;
    private volatile int lastSteps;
    private volatile int lastPending;
    private volatile boolean lastTruncated;

    /** Creates the segment. */
    public CommitSegment(IntentQueue queue, BooleanSupplier enabled, IntSupplier budget) {
        this.queue = queue;
        this.enabled = enabled;
        this.budget = budget;
    }

    /** Names the thread the segment may walk on. The first call binds the thread; a later call
     * from another thread is counted and never takes the ownership away, so a worker that reaches
     * the segment early cannot claim it. */
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

    public boolean ownerBound() {
        return ownerThread != null;
    }

    /** The intents the last walk applied, in the order it applied them. A walk that applied nothing
     * publishes the empty list, so a reader never has to tell "none" from "not reported". */
    public List<AppliedStep> appliedSteps() {
        return appliedSteps;
    }

    /** Walks the segment once, at the end of a tick. */
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
        long startedAt = System.nanoTime();
        int limit = Math.max(1, budget.getAsInt());
        lastBudget = limit;
        lastTruncated = false;
        int applied = 0;
        int touched = 0;
        List<AppliedStep> appliedThisWalk = null;
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
                long frozenOrder = queue.headOrder(world);
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
                if (appliedThisWalk == null) {
                    appliedThisWalk = new ArrayList<>();
                }
                appliedThisWalk.add(new AppliedStep(world, frozenOrder));
            }
            if (stopped) {
                break;
            }
        }
        appliedSteps = appliedThisWalk == null ? List.of() : List.copyOf(appliedThisWalk);
        steps += applied;
        walkNanos += System.nanoTime() - startedAt;
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

    public String mode() {
        return enabled.getAsBoolean() ? "execute" : "hold";
    }

    public long cursor() {
        return cursor;
    }

    /** Returns the order one world's walk expects next. */
    public synchronized long cursor(String worldKey) {
        return cursors.getOrDefault(worldKey, 0L);
    }

    public synchronized List<String> walkedWorlds() {
        return List.copyOf(cursors.keySet());
    }

    public long passes() {
        return passes;
    }

    public long steps() {
        return steps;
    }

    /** The wall time the walks spent applying intents to the world; observation only, it takes
     * part in no decision. */
    public long walkNanos() {
        return walkNanos;
    }

    public long refusals() {
        return refusals;
    }

    public long released() {
        return released;
    }

    public long foreignRuns() {
        return foreignRuns;
    }

    public long ownerConflicts() {
        return ownerConflicts;
    }

    public long budgetStops() {
        return budgetStops;
    }

    public int lastBudget() {
        return lastBudget;
    }

    public int lastSteps() {
        return lastSteps;
    }

    public int lastPending() {
        return lastPending;
    }

    public boolean lastTruncated() {
        return lastTruncated;
    }

    /** Clears live counters without rewinding the walk positions owned by the queue lifecycle. */
    public void reset() {
        passes = 0L;
        steps = 0L;
        walkNanos = 0L;
        refusals = 0L;
        released = 0L;
        budgetStops = 0L;
        lastBudget = 0;
        lastSteps = 0;
        lastPending = 0;
        lastTruncated = false;
    }
}
