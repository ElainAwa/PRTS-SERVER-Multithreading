package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;

/**
 * Runs the declared navigation-section phases in their original order. The serial form keeps the
 * span on the calling thread; the owner form will hand only the span phase to a worker while the
 * prefix and successor stay here.
 */
public final class NavigationSectionProgram {

    private NavigationSectionProgram() {
    }

    /** Runs prefix, span and successor on the current thread, in the original order. */
    public static void runSerial(Mob mob) {
        NavigationSectionStages stages = (NavigationSectionStages) mob;
        ProfilerFiller profiler = stages.prts$serverAiStepPrefix();
        NavigationSpanStage.run(mob.getNavigation());
        stages.prts$serverAiStepSuccessor(profiler);
    }
}
