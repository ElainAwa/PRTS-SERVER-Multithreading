/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.List;
import java.util.Objects;

/**
 * The declared program nodes of one mob AI step: the host entry of the AI step, the goal-selector
 * tick and the move control, each as one entity update program shape with the host segment it is
 * compared against. The host segments are the existing trace anchors, so this type adds no
 * instrumentation; the nodes are dormant evidence shapes and no production path builds one. The
 * move-control node declares the movement effect and its successor so the witness can say whether
 * the successor read an applied own write, but nothing here writes a world object.
 */
public final class AiStepProgram {

    /** The host segment of one declared AI step. */
    public record Node(EntityUpdateProgram program, String hostSegment) {

        public Node {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(hostSegment, "hostSegment");
            if (hostSegment.isEmpty()) {
                throw new IllegalArgumentException("a step node needs its host segment");
            }
        }

        /** Whether the step reads only; the move-control node declares the movement effect. */
        public boolean readOnly() {
            return !program.writes();
        }
    }

    /** The host segment of the mob AI step entry. */
    public static final String MOB_SERVER_AI_STEP = "mob.serverAiStep";
    /** The host segment of one goal-selector tick. */
    public static final String GOAL_SELECTOR_TICK = "goalSelector.tick";
    /** The host segment of one move-control tick. */
    public static final String MOVE_CONTROL_TICK = "moveControl.tick";

    private AiStepProgram() {
    }

    /** The three steps of one mob AI update, in the order the host runs them. */
    public static List<Node> defaultSteps(EntityUpdateProgram.Input input) {
        return List.of(serverAiStep(input), goalSelector(input), moveControl(input));
    }

    /** The mob AI step entry: a read-only program around the host step. */
    public static Node serverAiStep(EntityUpdateProgram.Input input) {
        return new Node(EntityUpdateProgram.dormant(EntityUpdateProgram.ProgramKind.MOB_TICK,
            input), MOB_SERVER_AI_STEP);
    }

    /** The goal-selector tick: a read-only program whose decision phase holds the goal cycle. */
    public static Node goalSelector(EntityUpdateProgram.Input input) {
        return new Node(EntityUpdateProgram.dormant(
            EntityUpdateProgram.ProgramKind.GOAL_SELECTOR, input), GOAL_SELECTOR_TICK);
    }

    /**
     * The move-control step: it declares the movement effect and the successor that may read the
     * applied own write. The declaration is the evidence shape only; this build applies no move.
     */
    public static Node moveControl(EntityUpdateProgram.Input input) {
        EntityUpdateProgram program = new EntityUpdateProgram(
            EntityUpdateProgram.ProgramKind.MOVE_CONTROL, input,
            new EntityUpdateProgram.RwSet(List.of(
                    new EntityUpdateProgram.Resource("self", "position")),
                List.of(new EntityUpdateProgram.Resource("self", "motion"))),
            java.util.Set.of(EntityUpdateProgram.Capability.LIVE_READ,
                EntityUpdateProgram.Capability.OWNER_LOCAL_WRITE),
            List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE, ProgramPhase.DECISION,
                ProgramPhase.CONTROLLED_WRITE, ProgramPhase.SUCCESSOR),
            List.of(new EntityUpdateProgram.AllowedEffect(EntityUpdateProgram.EffectKind.MOTION,
                ProgramPhase.CONTROLLED_WRITE)),
            List.of(new EntityUpdateProgram.CancelPoint(ProgramPhase.INPUT, "input"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.READ_COMPUTE, "read"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.DECISION, "decision")),
            List.of(new EntityUpdateProgram.Successor(EntityUpdateProgram.ProgramKind.MOVE_CONTROL,
                ProgramPhase.SUCCESSOR, "after-move")));
        return new Node(program, MOVE_CONTROL_TICK);
    }
}
