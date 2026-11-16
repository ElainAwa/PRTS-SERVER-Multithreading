/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The declared order of one goal cycle: the goal is asked whether it can be used, then started,
 * then ticked while it runs, and stopped when it no longer runs. The witness keeps one digest per
 * step, and the step sequence is mapped onto the phase marks of the goal-selector program so the
 * existing phase witness and host parity reference can compare it. The schedule is pure contract:
 * it observes the host order and runs nothing.
 */
public final class GoalSelectorSchedule {

    /** The steps of one goal cycle, in the order the host must take them. */
    public enum Step {
        CAN_USE,
        START,
        RUNNING_TICK,
        STOP
    }

    /** What one step sequence did; only an ordered sequence is a legal goal cycle. */
    public record Verdict(boolean ordered, Step offending, String detail) {

        public Verdict {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** One observed step and the digest the host produced for it. */
    public record Entry(Step step, long digest) {

        public Entry {
            Objects.requireNonNull(step, "step");
        }
    }

    private static final List<Step> CANONICAL =
        List.of(Step.CAN_USE, Step.START, Step.RUNNING_TICK, Step.STOP);

    private GoalSelectorSchedule() {
    }

    /** The canonical order of one goal cycle. */
    public static List<Step> canonical() {
        return CANONICAL;
    }

    /**
     * Checks one observed sequence: one can-use first, then one start, then any number of running
     * ticks, then one stop; a step out of that order is named as the offending step.
     */
    public static Verdict verify(List<Step> steps) {
        Objects.requireNonNull(steps, "steps");
        int index = 0;
        while (index < steps.size() && steps.get(index) == Step.CAN_USE) {
            if (index > 0) {
                return new Verdict(false, Step.CAN_USE, "duplicate-can-use");
            }
            index++;
        }
        if (index == 0) {
            return new Verdict(false, steps.isEmpty() ? null : steps.get(0), "missing-can-use");
        }
        if (index >= steps.size() || steps.get(index) != Step.START) {
            return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                "missing-start");
        }
        index++;
        if (index >= steps.size() || steps.get(index) != Step.RUNNING_TICK) {
            return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                "missing-running-tick");
        }
        while (index < steps.size() && steps.get(index) == Step.RUNNING_TICK) {
            index++;
        }
        if (index >= steps.size() || steps.get(index) != Step.STOP) {
            return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                "missing-stop");
        }
        index++;
        if (index != steps.size()) {
            return new Verdict(false, steps.get(index), "step-after-stop");
        }
        return new Verdict(true, null, "ordered");
    }

    /**
     * Maps one ordered cycle onto the goal-selector program witness: can-use is the input mark,
     * start the read mark, the single running tick the decision mark and stop the successor mark.
     * A cycle with no running tick or with more than one cannot be one witness and is refused.
     */
    public static ProgramWitness toProgramWitness(List<Entry> entries, EntityUpdateProgram.Input input) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(input, "input");
        List<Step> steps = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            steps.add(entry.step());
        }
        Verdict verdict = verify(steps);
        if (!verdict.ordered()) {
            throw new IllegalArgumentException("the goal cycle is not ordered: " + verdict.detail());
        }
        long canUse = digestOf(entries, Step.CAN_USE);
        long start = digestOf(entries, Step.START);
        long running = digestOf(entries, Step.RUNNING_TICK);
        long stop = digestOf(entries, Step.STOP);
        if (running == Long.MIN_VALUE) {
            throw new IllegalArgumentException("a witness cycle needs one running tick");
        }
        if (count(entries, Step.RUNNING_TICK) != 1) {
            throw new IllegalArgumentException("one witness holds one running tick");
        }
        EntityUpdateProgram program = EntityUpdateProgram.dormant(
            EntityUpdateProgram.ProgramKind.GOAL_SELECTOR, input);
        return ProgramWitness.builder(program)
            .mark(ProgramPhase.INPUT, 1L, canUse, false)
            .mark(ProgramPhase.READ_COMPUTE, 2L, start, false)
            .mark(ProgramPhase.DECISION, 3L, running, false)
            .mark(ProgramPhase.SUCCESSOR, 4L, stop, false)
            .build();
    }

    /** The host reference segments of one ordered cycle, as the read-only parity reference. */
    public static List<ProgramHostParity.HostSegment> hostSegments(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        ProgramWitness witness = toProgramWitness(entries, defaultInput());
        List<ProgramHostParity.HostSegment> segments = new ArrayList<>(4);
        for (ProgramPhase phase : List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE,
            ProgramPhase.DECISION, ProgramPhase.SUCCESSOR)) {
            ProgramWitness.PhaseMark mark = witness.mark(phase);
            segments.add(ProgramHostParity.segment(phase, mark.outputDigest()));
        }
        return List.copyOf(segments);
    }

    private static long digestOf(List<Entry> entries, Step step) {
        for (Entry entry : entries) {
            if (entry.step() == step) {
                return entry.digest();
            }
        }
        return Long.MIN_VALUE;
    }

    private static int count(List<Entry> entries, Step step) {
        int count = 0;
        for (Entry entry : entries) {
            if (entry.step() == step) {
                count++;
            }
        }
        return count;
    }

    /** The placeholder input the witness mapping needs; only the program kind matters here. */
    private static EntityUpdateProgram.Input defaultInput() {
        return new EntityUpdateProgram.Input("minecraft:overworld", 1L,
            new io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity(
                "minecraft:overworld", 1L, 1L, 2L, 3, 4), 1L, 1L, 1L);
    }
}
