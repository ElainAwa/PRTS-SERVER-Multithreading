/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The declared order of goal cycles: one goal cycle asks whether the goal can be used, starts it,
 * ticks it while it runs and stops it when it no longer runs. One host tick may fold several goals,
 * so a witness holds any number of cycles and folds them into the four phase marks of the
 * goal-selector program. The schedule is pure contract: it observes the host order and runs nothing.
 */
public final class GoalSelectorSchedule {

    /** The steps of one goal cycle, in the order the host must take them. */
    public enum Step {
        CAN_USE,
        START,
        RUNNING_TICK,
        STOP
    }

    /** What one step sequence did; only an ordered sequence of cycles is legal. */
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
     * Checks one observed sequence of goal cycles: every cycle is a can-use, a start, at least one
     * running tick and a stop, and one host tick may fold several of them. Every deviation names
     * the step that broke the order.
     */
    public static Verdict verify(List<Step> steps) {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            return new Verdict(false, null, "missing-can-use");
        }
        int index = 0;
        while (index < steps.size()) {
            if (steps.get(index) != Step.CAN_USE) {
                return new Verdict(false, steps.get(index), "missing-can-use");
            }
            index++;
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
        }
        return new Verdict(true, null, "ordered");
    }

    /**
     * Folds one ordered sequence of goal cycles into the goal-selector program witness: every
     * can-use digest folds into the input mark, every start into the read mark, every running tick
     * into the decision mark and every stop into the successor mark.
     */
    public static ProgramWitness toProgramWitness(List<Entry> entries,
                                                  EntityUpdateProgram.Input input) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(input, "input");
        Verdict verdict = verify(stepList(entries));
        if (!verdict.ordered()) {
            throw new IllegalArgumentException("the goal cycles are not ordered: " + verdict.detail());
        }
        EntityUpdateProgram program = EntityUpdateProgram.dormant(
            EntityUpdateProgram.ProgramKind.GOAL_SELECTOR, input);
        return ProgramWitness.builder(program)
            .mark(ProgramPhase.INPUT, 1L, fold(entries, Step.CAN_USE), false)
            .mark(ProgramPhase.READ_COMPUTE, 2L, fold(entries, Step.START), false)
            .mark(ProgramPhase.DECISION, 3L, fold(entries, Step.RUNNING_TICK), false)
            .mark(ProgramPhase.SUCCESSOR, 4L, fold(entries, Step.STOP), false)
            .build();
    }

    /** The host reference segments of one folded goal sequence, as the read-only parity reference. */
    public static List<ProgramHostParity.HostSegment> hostSegments(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        ProgramWitness witness = toProgramWitness(entries, defaultInput());
        List<ProgramHostParity.HostSegment> segments = new ArrayList<>(4);
        for (ProgramPhase phase : List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE,
            ProgramPhase.DECISION, ProgramPhase.SUCCESSOR)) {
            segments.add(ProgramHostParity.segment(phase, witness.mark(phase).outputDigest()));
        }
        return List.copyOf(segments);
    }

    private static List<Step> stepList(List<Entry> entries) {
        List<Step> steps = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            steps.add(entry.step());
        }
        return steps;
    }

    private static long fold(List<Entry> entries, Step step) {
        long folded = 0xcbf29ce484222325L;
        int ordinal = 0;
        for (Entry entry : entries) {
            if (entry.step() == step) {
                folded = (folded ^ entry.digest()) * 0x100000001b3L;
                folded = (folded ^ ++ordinal) * 0x100000001b3L;
            }
        }
        return folded;
    }

    /** The placeholder input the witness mapping needs; only the program kind matters here. */
    private static EntityUpdateProgram.Input defaultInput() {
        return new EntityUpdateProgram.Input("minecraft:overworld", 1L,
            new EntityRowIdentity("minecraft:overworld", 1L, 1L, 2L, 3, 4), 1L, 1L, 1L);
    }
}
