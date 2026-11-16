/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The declared order of one brain tick: the sensors tick first and produce the memories, then the
 * activities consume them - each activity is started, ticked while it runs and stopped when it
 * ends, and one brain tick may fold several sensors and several activities. The witness folds the
 * sub-steps into the phase marks of the brain-tick program; the order is pure contract and runs
 * nothing.
 */
public final class BrainStepOrder {

    /** The steps of one brain tick, in the order the activities must consume them. */
    public enum Step {
        SENSOR_TICK,
        ACTIVITY_START,
        ACTIVITY_TICK,
        ACTIVITY_STOP
    }

    /** What one step sequence did; only an ordered sequence is a legal brain tick. */
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
        List.of(Step.SENSOR_TICK, Step.ACTIVITY_START, Step.ACTIVITY_TICK, Step.ACTIVITY_STOP);

    private BrainStepOrder() {
    }

    /** The canonical order of one sensor-then-activity step. */
    public static List<Step> canonical() {
        return CANONICAL;
    }

    /**
     * Checks one observed sequence: at least one sensor tick first, then one or more activity
     * cycles (start, at least one tick, stop); every deviation names the step that broke the order.
     */
    public static Verdict verify(List<Step> steps) {
        Objects.requireNonNull(steps, "steps");
        int index = 0;
        int sensors = 0;
        while (index < steps.size() && steps.get(index) == Step.SENSOR_TICK) {
            index++;
            sensors++;
        }
        if (sensors == 0) {
            return new Verdict(false, steps.isEmpty() ? null : steps.get(0), "missing-sensor-tick");
        }
        if (index >= steps.size()) {
            return new Verdict(false, null, "missing-activity-start");
        }
        while (index < steps.size()) {
            if (steps.get(index) != Step.ACTIVITY_START) {
                return new Verdict(false, steps.get(index), "missing-activity-start");
            }
            index++;
            if (index >= steps.size() || steps.get(index) != Step.ACTIVITY_TICK) {
                return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                    "missing-activity-tick");
            }
            while (index < steps.size() && steps.get(index) == Step.ACTIVITY_TICK) {
                index++;
            }
            if (index >= steps.size() || steps.get(index) != Step.ACTIVITY_STOP) {
                return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                    "missing-activity-stop");
            }
            index++;
        }
        return new Verdict(true, null, "ordered");
    }

    /**
     * Folds one ordered brain tick into the brain-tick program witness: the sensor ticks fold into
     * the input mark, the activity starts into the read mark, the activity ticks into the decision
     * mark and the activity stops into the successor mark.
     */
    public static ProgramWitness toProgramWitness(List<Entry> entries,
                                                  EntityUpdateProgram.Input input) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(input, "input");
        Verdict verdict = verify(stepList(entries));
        if (!verdict.ordered()) {
            throw new IllegalArgumentException("the brain tick is not ordered: " + verdict.detail());
        }
        EntityUpdateProgram program = EntityUpdateProgram.dormant(
            EntityUpdateProgram.ProgramKind.BRAIN_TICK, input);
        return ProgramWitness.builder(program)
            .mark(ProgramPhase.INPUT, 1L, fold(entries, Step.SENSOR_TICK), false)
            .mark(ProgramPhase.READ_COMPUTE, 2L, fold(entries, Step.ACTIVITY_START), false)
            .mark(ProgramPhase.DECISION, 3L, fold(entries, Step.ACTIVITY_TICK), false)
            .mark(ProgramPhase.SUCCESSOR, 4L, fold(entries, Step.ACTIVITY_STOP), false)
            .build();
    }

    /** The read-only host reference segments of one folded brain tick. */
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
