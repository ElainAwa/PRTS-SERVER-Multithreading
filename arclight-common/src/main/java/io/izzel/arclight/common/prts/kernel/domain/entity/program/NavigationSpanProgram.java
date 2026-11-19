/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.List;
import java.util.Set;

/** The dormant navigation span shape: the host PathNavigation.tick/followThePath segment between
 * the AI step entry and the move/look/jump controls. It declares the navigation read/write set and
 * the successor move-control step; no production path builds it and no world object is written. */
public final class NavigationSpanProgram {

    /** The host segment of PathNavigation.tick, including followThePath/stuck. */
    public static final String NAVIGATION_TICK = "navigation.tick";

    private NavigationSpanProgram() {
    }

    public static AiStepProgram.Node navigation(EntityUpdateProgram.Input input) {
        EntityUpdateProgram program = new EntityUpdateProgram(
            EntityUpdateProgram.ProgramKind.NAVIGATION, input,
            new EntityUpdateProgram.RwSet(List.of(
                new EntityUpdateProgram.Resource("self", "path"),
                new EntityUpdateProgram.Resource("self", "cursor"),
                new EntityUpdateProgram.Resource("self", "stuck-state")),
                List.of(new EntityUpdateProgram.Resource("self", "path"),
                    new EntityUpdateProgram.Resource("self", "stuck-state"))),
            Set.of(EntityUpdateProgram.Capability.LIVE_READ,
                EntityUpdateProgram.Capability.OWNER_LOCAL_WRITE),
            List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE, ProgramPhase.DECISION,
                ProgramPhase.CONTROLLED_WRITE, ProgramPhase.SUCCESSOR),
            List.of(new EntityUpdateProgram.AllowedEffect(EntityUpdateProgram.EffectKind.NAVIGATION,
                ProgramPhase.CONTROLLED_WRITE)),
            List.of(new EntityUpdateProgram.CancelPoint(ProgramPhase.INPUT, "input"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.READ_COMPUTE, "read"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.DECISION, "decision")),
            List.of(new EntityUpdateProgram.Successor(EntityUpdateProgram.ProgramKind.MOVE_CONTROL,
                ProgramPhase.SUCCESSOR, "move-control-after-navigation")));
        return new AiStepProgram.Node(program, NAVIGATION_TICK);
    }
}
