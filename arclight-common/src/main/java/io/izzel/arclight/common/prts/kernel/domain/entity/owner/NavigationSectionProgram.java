package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;

/**
 * Runs the declared navigation-section phases in their original order. The serial form keeps the
 * span on the calling thread; the phase form lets an owner batch keep the span on a worker while the
 * prefix and successor stay on the host.
 */
public final class NavigationSectionProgram {

    /** The profiler one prefix opened and the mob its successor must run on. */
    public record Prefix(Mob mob, ProfilerFiller profiler) {
    }

    private NavigationSectionProgram() {
    }

    /** Runs the host prefix on the current thread and returns the profiler it opened. */
    public static Prefix begin(Mob mob) {
        ProfilerFiller profiler = ((NavigationSectionStages) mob).prts$serverAiStepPrefix();
        return new Prefix(mob, profiler);
    }

    /** Runs the navigation span for one prefix on the current thread. */
    public static void runSpan(Prefix prefix) {
        NavigationSpanStage.run(prefix.mob().getNavigation());
    }

    /** Runs the host successor for one prefix on the current thread. */
    public static void runSuccessor(Prefix prefix) {
        ((NavigationSectionStages) prefix.mob()).prts$serverAiStepSuccessor(prefix.profiler());
    }

    /** Runs prefix, span and successor on the current thread, in the original order. */
    public static void runSerial(Mob mob) {
        Prefix prefix = begin(mob);
        runSpan(prefix);
        runSuccessor(prefix);
    }
}
