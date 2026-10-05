/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/** The three rungs the wait axis gives way along when a wait crosses its upper bound: tighten the
 * bound, drop the parallel degree of the domains whose waits crossed, defer the whole segment to
 * the next tick in order. Every rung carries its trigger, the action it would take, the signal that
 * shows the pressure and the condition it has to meet before it may return, and every rung counts
 * how often the ladder entered it, how often the action ran and how often it came back.
 *
 * <p>The code every rung raises is the pressure that walked it - a wait over the bound - and not
 * the action a rung would take, so a rung can never be read as the refusal it answers with.
 *
 * <p>No action of this ladder runs in this build. The flag that would allow one is read on every
 * call and is off by default, so the effective count stays at zero next to a non-zero entered
 * count, and a rung that was reached is never read as a rung that acted. The return gate has two
 * conditions and needs both: a run of clean ticks long enough, and the progress signal of the wait
 * point moving again. A rung whose second condition is not bound can never pass its gate, because a
 * condition that was never wired is not a condition that is met. */
public final class WaitLadder {

    /** One rung of the wait ladder. */
    public enum Level {
        NONE,
        A1,
        A2,
        A3
    }

    public record Rung(Level level, RejectCode code, String trigger, String action, String signal,
                       String returnCondition) {
    }

    public record Counters(Level level, long entered, long effective, long returned) {
    }

    /** What one advance did: the rung the ladder now stands on, the rungs it walked through and
     * whether it skipped the order to get there. */
    public record Advance(Level reached, List<Level> entered, boolean skipped) {
    }

    /** The return gate of one rung: the clean run against the window, the second condition and the
     * name of what still blocks the return. */
    public record Gate(Level level, int windowTicks, long cleanTicks, boolean secondBound,
                       String secondSource, boolean secondSatisfied, boolean ready, String blockedBy) {
    }

    public record Sign(Level deepest, long enteredTick, long ticksObserved, long cleanTicks,
                       long skippedCount, boolean actionsEnabled) {
    }

    private static final List<Rung> RUNGS = List.of(
        new Rung(Level.A1, RejectCode.WAIT_BOUND_EXCEEDED,
            "a single wait crossed its upper bound, or the bound was hit at least once in the tick",
            "tighten the wait bound one step inside the configured bound",
            "wait overruns and bound hits",
            "no wait crossed the bound for the window and the progress signal of the wait point "
                + "moved again"),
        new Rung(Level.A2, RejectCode.WAIT_BOUND_EXCEEDED,
            "a wait still crosses the bound after the tightened bound is in force",
            "drop the parallel degree of the domains whose waits crossed",
            "parallel degree and join wait time",
            "the wait time fell back and no wait crossed the bound for the window"),
        new Rung(Level.A3, RejectCode.WAIT_BOUND_EXCEEDED,
            "a wait still crosses the bound after the parallel degree was dropped",
            "defer the whole segment to the next tick, in order",
            "deferred jobs and queue depth",
            "the queue depth fell back and the progress signal moved again"));

    private final BooleanSupplier actionsEnabled;
    private final LongAdder[] entered = freshCounters();
    private final LongAdder[] effective = freshCounters();
    private final LongAdder[] returned = freshCounters();
    private final Map<RejectCode, LongAdder> codes = new LinkedHashMap<>();
    private final Map<Level, Case> secondConditions = new EnumMap<>(Level.class);
    private final LongAdder skipped = new LongAdder();
    private Level deepest = Level.NONE;
    private long enteredTick;
    private long ticksObserved;
    private long cleanTicks;

    public WaitLadder(BooleanSupplier actionsEnabled) {
        this.actionsEnabled = actionsEnabled == null ? () -> false : actionsEnabled;
    }

    public List<Rung> rungs() {
        return RUNGS;
    }

    public Rung rung(Level level) {
        for (Rung rung : RUNGS) {
            if (rung.level() == level) {
                return rung;
            }
        }
        return null;
    }

    /** Declares what tells a rung that the pressure behind it is gone. A rung without a binding
     * answers unbound and can never pass its gate. */
    public synchronized void bindSecondCondition(Level level, String source,
                                                 BooleanSupplier condition) {
        if (level == null || condition == null) {
            return;
        }
        secondConditions.put(level, new Case(source, condition));
    }

    /** Advances the ladder to the rung a crossed wait maps to. The order may not be skipped, so the
     * ladder walks every rung between where it stands and the target; a single call that has to walk
     * more than one rung is counted as a skip and raises the exhausted code. */
    public synchronized Advance noteEntered(Level target, long tickIndex) {
        if (target == null || target == Level.NONE || target.ordinal() <= 0) {
            return new Advance(deepest, List.of(), false);
        }
        boolean isSkip = target.ordinal() > deepest.ordinal() + 1;
        if (isSkip) {
            skipped.increment();
            count(RejectCode.TICK_BUDGET_EXHAUSTED);
        }
        List<Level> walked = new ArrayList<>(target.ordinal() - deepest.ordinal());
        Level[] levels = Level.values();
        for (int index = deepest.ordinal() + 1; index <= target.ordinal(); index++) {
            Level level = levels[index];
            entered[level.ordinal() - 1].increment();
            Rung rung = rung(level);
            if (rung != null) {
                count(rung.code());
            }
            walked.add(level);
        }
        if (target.ordinal() > deepest.ordinal()) {
            deepest = target;
            enteredTick = tickIndex;
        }
        return new Advance(deepest, List.copyOf(walked), isSkip);
    }

    /** Counts an action that ran. The flag is read here, so a rung that was entered while the ladder
     * may not act reports zero instead of a false effective. */
    public synchronized boolean noteEffective(Level level) {
        if (!actionsEnabled.getAsBoolean() || level == null || level == Level.NONE) {
            return false;
        }
        effective[level.ordinal() - 1].increment();
        return true;
    }

    /** Counts a return, and drops the rung the ladder stands on back to whatever is still in
     * force. */
    public synchronized boolean noteReturned(Level level) {
        if (!actionsEnabled.getAsBoolean() || level == null || level == Level.NONE) {
            return false;
        }
        returned[level.ordinal() - 1].increment();
        if (level == deepest) {
            deepest = highestInForce();
        }
        return true;
    }

    /** One tick of the gate: a tick that carried a crossed wait ends the clean run. */
    public synchronized void noteTick(boolean crossed) {
        ticksObserved++;
        if (crossed) {
            cleanTicks = 0L;
        } else {
            cleanTicks++;
        }
    }

    /** Answers whether a rung may return: the clean run has to reach the window and the rung's own
     * condition, the recovered progress signal, has to be satisfied. Either one missing keeps the
     * rung in force. */
    public synchronized Gate gate(Level level, int windowTicks) {
        int window = Math.max(1, windowTicks);
        Case condition = level == null ? null : secondConditions.get(level);
        boolean bound = condition != null && condition.condition != null;
        boolean satisfied = bound && condition.condition.getAsBoolean();
        String blockedBy;
        if (cleanTicks < window) {
            blockedBy = "clean_ticks";
        } else if (!bound) {
            blockedBy = "second_condition_unbound";
        } else if (!satisfied) {
            blockedBy = "second_condition";
        } else {
            blockedBy = "none";
        }
        return new Gate(level, window, cleanTicks, bound, bound ? condition.source : "unbound",
            satisfied, cleanTicks >= window && satisfied, blockedBy);
    }

    public synchronized Sign sign() {
        return new Sign(deepest, enteredTick, ticksObserved, cleanTicks, skipped.sum(),
            actionsEnabled.getAsBoolean());
    }

    public synchronized Counters counters(Level level) {
        if (level == null || level == Level.NONE) {
            return new Counters(Level.NONE, 0L, 0L, 0L);
        }
        int index = level.ordinal() - 1;
        return new Counters(level, entered[index].sum(), effective[index].sum(),
            returned[index].sum());
    }

    public synchronized List<Counters> counters() {
        List<Counters> all = new ArrayList<>(RUNGS.size());
        for (Rung rung : RUNGS) {
            all.add(counters(rung.level()));
        }
        return all;
    }

    public synchronized long enteredTotal() {
        return total(entered);
    }

    public synchronized long effectiveTotal() {
        return total(effective);
    }

    public synchronized long returnedTotal() {
        return total(returned);
    }

    public synchronized long codeCount(RejectCode code) {
        LongAdder counter = code == null ? null : codes.get(code);
        return counter == null ? 0L : counter.sum();
    }

    public synchronized void reset() {
        for (LongAdder counter : entered) {
            counter.reset();
        }
        for (LongAdder counter : effective) {
            counter.reset();
        }
        for (LongAdder counter : returned) {
            counter.reset();
        }
        codes.clear();
        skipped.reset();
        deepest = Level.NONE;
        enteredTick = 0L;
        ticksObserved = 0L;
        cleanTicks = 0L;
    }

    private Level highestInForce() {
        Level[] levels = Level.values();
        for (int index = levels.length - 1; index > 0; index--) {
            if (entered[index - 1].sum() > returned[index - 1].sum()) {
                return levels[index];
            }
        }
        return Level.NONE;
    }

    private void count(RejectCode code) {
        codes.computeIfAbsent(code, key -> new LongAdder()).increment();
    }

    private static LongAdder[] freshCounters() {
        LongAdder[] counters = new LongAdder[Level.values().length - 1];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = new LongAdder();
        }
        return counters;
    }

    private static long total(LongAdder[] counters) {
        long sum = 0L;
        for (LongAdder counter : counters) {
            sum += counter.sum();
        }
        return sum;
    }

    private record Case(String source, BooleanSupplier condition) {
    }
}
