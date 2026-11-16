/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.List;
import java.util.Objects;

/**
 * The declared program nodes of one brain step: the brain tick and the sensor ticks it hosts, each
 * as one read-only update program shape with the existing host trace anchor it is compared against.
 * No instrumentation is added and no production path builds a node; the brain tick is the outer
 * step and the sensor tick runs inside it, so the nodes are evidence shapes only.
 */
public final class BrainStepProgram {

    /** The host segment of one brain tick. */
    public static final String BRAIN_TICK = "brain.tick";
    /** The host segment of one sensor tick, hosted by the brain tick. */
    public static final String SENSOR_TICK = "sensor.tick";

    /** One declared brain step: its program shape and its host segment. */
    public record Node(EntityUpdateProgram program, String hostSegment) {

        public Node {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(hostSegment, "hostSegment");
            if (hostSegment.isEmpty()) {
                throw new IllegalArgumentException("a brain step needs its host segment");
            }
        }

        public boolean readOnly() {
            return !program.writes();
        }
    }

    private BrainStepProgram() {
    }

    /** The brain tick, the outer step of one update. */
    public static Node brainTick(EntityUpdateProgram.Input input) {
        return new Node(EntityUpdateProgram.dormant(EntityUpdateProgram.ProgramKind.BRAIN_TICK,
            input), BRAIN_TICK);
    }

    /** One sensor tick, hosted by the brain tick before the activities consume its memories. */
    public static Node sensorTick(EntityUpdateProgram.Input input) {
        return new Node(EntityUpdateProgram.dormant(EntityUpdateProgram.ProgramKind.SENSOR_TICK,
            input), SENSOR_TICK);
    }

    /** Both nodes in host nesting order: the brain tick first, the sensor tick it hosts second. */
    public static List<Node> nestedSteps(EntityUpdateProgram.Input input) {
        return List.of(brainTick(input), sensorTick(input));
    }
}
