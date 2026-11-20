/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.Set;

/** Runs the {@code Mob.serverAiStep} prefix through the one program entry the transformed host
 * calls: with a bound owner context the prefix is declared as one segment, without one the host
 * stage runs exactly as before. */
final class ServerAiStepPrefixProgram {

    private static final EntityUpdateSegmentPolicy.Segment SEGMENT =
        EntityUpdateSegmentPolicy.Segment.SERVER_AI_STEP_PREFIX;
    private static final Set<EntityUpdateSegmentPolicy.Segment> DECLARED = Set.of(SEGMENT);

    private ServerAiStepPrefixProgram() {
    }

    /** The single entry the transformed host calls; a missing or mismatched context delegates. */
    static ProfilerFiller run(Mob mob) {
        EntityUpdateProgramContext context = EntityUpdateProgramContext.current();
        boolean matches = context != null && context.matches(mob);
        return run(context, matches, (NavigationSectionStages) mob);
    }

    static ProfilerFiller run(EntityUpdateProgramContext context, boolean identityMatches,
                              NavigationSectionStages stages) {
        if (context == null || !identityMatches || !EntityUpdateSegmentPolicy.admitsWorker(DECLARED)) {
            return stages.prts$serverAiStepPrefix();
        }
        if (!context.enterSegment(SEGMENT)) {
            throw new IllegalStateException("prefix segment entered twice for one owner row");
        }
        try {
            ProfilerFiller profiler = stages.prts$serverAiStepPrefix();
            context.noteSegmentExecuted(SEGMENT);
            return profiler;
        } finally {
            context.leaveSegment(SEGMENT);
        }
    }

    /** Whether one row can declare this prefix: a villager with neither selector holding a goal. */
    static boolean admits(Villager villager) {
        if (villager == null || villager.isNoAi()) {
            return false;
        }
        if (!villager.goalSelector.getAvailableGoals().isEmpty()
            || !villager.targetSelector.getAvailableGoals().isEmpty()) {
            return false;
        }
        return EntityUpdateSegmentPolicy.admitsWorker(DECLARED);
    }
}
