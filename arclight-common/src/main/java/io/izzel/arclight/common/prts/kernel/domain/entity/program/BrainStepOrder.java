/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The declared order of one brain tick: the sensors tick first and produce the memories, then each
 * activity is started, ticked while it runs and stopped when it ends. The witness keeps one digest
 * per step and maps the sequence onto the phase marks of the brain-tick program, so the existing
 * phase witness and read-only host parity reference compare it. The order is pure contract: it
 * observes nothing by itself and runs nothing.
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

    /** The canonical order of one brain tick. */
    public static List<Step> canonical() {
        return CANONICAL;
    }

    /**
     * Checks one observed sequence: at least one sensor tick first, then one activity start, then
     * any number of activity ticks, then one activity stop; every deviation names its step.
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
        if (index >= steps.size() || steps.get(index) != Step.ACTIVITY_START) {
            return new Verdict(false, index < steps.size() ? steps.get(index) : null,
                "missing-activity-start");
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
        if (index != steps.size()) {
            return new Verdict(false, steps.get(index), "step-after-stop");
        }
        return new Verdict(true, null, "ordered");
    }

    /**
     * Maps one ordered brain tick onto the brain-tick program witness: the first sensor tick is the
     * input mark, the activity start the read mark, the first activity tick the decision mark and the
     * activity stop the successor mark. A sequence without a tick cannot be one witness.
     */
    public static ProgramWitness toProgramWitness(List<Entry> entries, EntityUpdateProgram.Input input) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(input, "input");
        List<Step> steps = stepList(entries);
        Verdict verdict = verify(steps);
        if (!verdict.ordered()) {
            throw new IllegalArgumentException("the brain tick is not ordered: " + verdict.detail());
        }
        if (count(entries, Step.SENSOR_TICK) != 1 || count(entries, Step.ACTIVITY_TICK) != 1) {
            throw new IllegalArgumentException("one witness holds one sensor tick and one activity tick");
        }
        long sensor = digestOf(entries, Step.SENSOR_TICK);
        long start = digestOf(entries, Step.ACTIVITY_START);
        long tick = digestOf(entries, Step.ACTIVITY_TICK);
        long stop = digestOf(entries, Step.ACTIVITY_STOP);
        EntityUpdateProgram program = EntityUpdateProgram.dormant(
            EntityUpdateProgram.ProgramKind.BRAIN_TICK, input);
        return ProgramWitness.builder(program)
            .mark(ProgramPhase.INPUT, 1L, sensor, false)
            .mark(ProgramPhase.READ_COMPUTE, 2L, start, false)
            .mark(ProgramPhase.DECISION, 3L, tick, false)
            .mark(ProgramPhase.SUCCESSOR, 4L, stop, false)
            .build();
    }

    /** The read-only host reference segments of one ordered brain tick. */
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
            new EntityRowIdentity("minecraft:overworld", 1L, 1L, 2L, 3, 4), 1L, 1L, 1L);
    }
}
