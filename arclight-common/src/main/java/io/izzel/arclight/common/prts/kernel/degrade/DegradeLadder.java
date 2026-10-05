/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/** The five rungs a tick gives way along when a class spends more than its share. Every rung carries
 * its trigger, the action it would take, the signal that shows the pressure and the condition it
 * has to meet before it may return; every rung counts how often the ladder entered it, how often
 * the action ran and how often it came back.
 *
 * <p>No action of this ladder runs in this build. The flag that would allow one is read on every
 * call and is off by default, so the effective count stays at zero next to a non-zero entered
 * count, and a rung that was reached is never read as a rung that acted. */
public final class DegradeLadder {

    /** The run of clean ticks a rung needs before it may return. A declared value, not a measured
     * one; the gate itself takes whatever window it is asked for. */
    public static final int DECLARED_RETURN_TICKS = 3;

    /** One rung of the ladder. */
    public record Rung(DegradeLevel level, RejectCode code, String trigger, String action,
                       String signal, String returnCondition) {
    }

    /** The three counters of one rung. */
    public record Counters(DegradeLevel level, long entered, long effective, long returned) {
    }

    /** What one advance did: the rung the ladder now stands on, the rungs it walked through and
     * whether it skipped the order to get there. */
    public record Advance(DegradeLevel reached, List<DegradeLevel> entered, boolean skipped) {
    }

    /** The return gate of one rung: the clean run against the window, the second condition and the
     * name of what still blocks the return. */
    public record Gate(DegradeLevel level, int windowTicks, long cleanTicks, boolean secondBound,
                       String secondSource, boolean secondSatisfied, boolean ready, String blockedBy) {
    }

    private static final List<Rung> RUNGS = List.of(
        new Rung(DegradeLevel.B1, RejectCode.QUOTA_EXCEEDED,
            "the AI share is over its margin",
            "cut the AI decision frequency",
            "AI frequency cuts",
            "the share is back inside its margin and the class stayed clean for the window"),
        new Rung(DegradeLevel.B2, RejectCode.RECOMPUTE_UNBOUNDED,
            "the graph recompute share is over its margin",
            "postpone the graph recompute",
            "recompute queue depth",
            "the queue depth has fallen back and no recompute was refused for its budget"),
        new Rung(DegradeLevel.B3, RejectCode.SCALE_BUDGET_EXCEEDED,
            "the entity or block entity share is over its margin",
            "merge the entity and block entity batches",
            "batch merge rate and deferred batch count",
            "the deferred batch count is zero"),
        new Rung(DegradeLevel.B4, RejectCode.QUEUE_CAP_EXCEEDED,
            "the event share is over its margin",
            "queue the events instead of dispatching them",
            "event queue depth",
            "the queue depth and the wait time have fallen back"),
        new Rung(DegradeLevel.B5, RejectCode.TICK_BUDGET_EXHAUSTED,
            "the four earlier rungs are in force and a class is still over its margin",
            "shrink what this tick covers and rebuild the next tick against the degraded shares",
            "ticks spent in the degraded state",
            "the next tick rebuilt against the degraded shares passes its check"));

    private final BooleanSupplier actionsEnabled;
    private final LongAdder[] entered = freshCounters();
    private final LongAdder[] effective = freshCounters();
    private final LongAdder[] returned = freshCounters();
    private final Map<RejectCode, LongAdder> codes = new LinkedHashMap<>();
    private final Map<DegradeLevel, Case> secondConditions = new EnumMap<>(DegradeLevel.class);
    private final LongAdder skipped = new LongAdder();
    private DegradeLevel deepest = DegradeLevel.NONE;
    private long enteredTick;
    private long ticksObserved;
    private long cleanTicks;

    public DegradeLadder(BooleanSupplier actionsEnabled) {
        this.actionsEnabled = actionsEnabled == null ? () -> false : actionsEnabled;
    }

    public List<Rung> rungs() {
        return RUNGS;
    }

    public Rung rung(DegradeLevel level) {
        for (Rung rung : RUNGS) {
            if (rung.level() == level) {
                return rung;
            }
        }
        return null;
    }

    /** Declares what tells a rung that the pressure behind it is gone. A rung without a binding
     * answers unbound and can never pass its gate: a condition that was never wired is not a
     * condition that is met. */
    public synchronized void bindSecondCondition(DegradeLevel level, String source,
                                                 BooleanSupplier condition) {
        if (level == null || condition == null) {
            return;
        }
        secondConditions.put(level, new Case(source, condition));
    }

    /** Advances the ladder to the rung a class overrun maps to. The order may not be skipped, so
     * the ladder walks every rung between where it stands and the target; a single call that has
     * to walk more than one rung is counted as a skip and raises the exhausted code. */
    public synchronized Advance noteEntered(DegradeLevel target, long tickIndex) {
        if (target == null || target == DegradeLevel.NONE || target.ordinal() <= 0) {
            return new Advance(deepest, List.of(), false);
        }
        boolean isSkip = target.ordinal() > deepest.ordinal() + 1;
        if (isSkip) {
            skipped.increment();
            count(RejectCode.TICK_BUDGET_EXHAUSTED);
        }
        List<DegradeLevel> walked = new ArrayList<>(target.ordinal() - deepest.ordinal());
        DegradeLevel[] levels = DegradeLevel.values();
        for (int index = deepest.ordinal() + 1; index <= target.ordinal(); index++) {
            DegradeLevel level = levels[index];
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

    /** Counts an action that ran. The flag is read here, so a rung that was entered while the
     * ladder may not act reports zero instead of a false effective. */
    public synchronized boolean noteEffective(DegradeLevel level) {
        if (!actionsEnabled.getAsBoolean() || level == null || level == DegradeLevel.NONE) {
            return false;
        }
        effective[level.ordinal() - 1].increment();
        return true;
    }

    /** Counts a return, and drops the rung the ladder stands on back to whatever is still in
     * force. */
    public synchronized boolean noteReturned(DegradeLevel level) {
        if (!actionsEnabled.getAsBoolean() || level == null || level == DegradeLevel.NONE) {
            return false;
        }
        returned[level.ordinal() - 1].increment();
        if (level == deepest) {
            deepest = highestInForce();
        }
        return true;
    }

    /** One tick of the gate: a tick that carried an overrun ends the clean run. */
    public synchronized void noteTick(boolean overrun) {
        ticksObserved++;
        if (overrun) {
            cleanTicks = 0L;
        } else {
            cleanTicks++;
        }
    }

    /** Answers whether a rung may return: the clean run has to reach the window and the rung's own
     * condition has to be satisfied. Either one missing keeps the rung in force. */
    public synchronized Gate gate(DegradeLevel level, int windowTicks) {
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
        return new Gate(level, window, cleanTicks, bound,
            bound ? condition.source : "unbound", satisfied,
            cleanTicks >= window && satisfied, blockedBy);
    }

    public synchronized Sign sign() {
        return new Sign(deepest, enteredTick, ticksObserved, cleanTicks, skipped.sum(),
            actionsEnabled.getAsBoolean());
    }

    /** Where the ladder stands and how far the gate has come. */
    public record Sign(DegradeLevel deepest, long enteredTick, long ticksObserved, long cleanTicks,
                       long skippedCount, boolean actionsEnabled) {
    }

    public synchronized Counters counters(DegradeLevel level) {
        if (level == null || level == DegradeLevel.NONE) {
            return new Counters(DegradeLevel.NONE, 0L, 0L, 0L);
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

    /** How often the ladder raised one code. The codes are counted whether or not an action ran:
     * a pressure that was seen is a pressure that was reported. */
    public synchronized long codeCount(RejectCode code) {
        LongAdder counter = code == null ? null : codes.get(code);
        return counter == null ? 0L : counter.sum();
    }

    public synchronized Map<RejectCode, Long> codeCounts() {
        Map<RejectCode, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<RejectCode, LongAdder> entry : codes.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().sum());
        }
        return counts;
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
        deepest = DegradeLevel.NONE;
        enteredTick = 0L;
        ticksObserved = 0L;
        cleanTicks = 0L;
    }

    private DegradeLevel highestInForce() {
        DegradeLevel[] levels = DegradeLevel.values();
        for (int index = levels.length - 1; index > 0; index--) {
            if (entered[index - 1].sum() > returned[index - 1].sum()) {
                return levels[index];
            }
        }
        return DegradeLevel.NONE;
    }

    private void count(RejectCode code) {
        codes.computeIfAbsent(code, key -> new LongAdder()).increment();
    }

    private static LongAdder[] freshCounters() {
        LongAdder[] counters = new LongAdder[DegradeLevel.values().length - 1];
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
