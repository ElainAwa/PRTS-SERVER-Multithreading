package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Declares the three phases of one mob navigation section so a driver can run the prefix, let an
 * owner run the navigation span, and then run the successor on the host, all through the same
 * stage implementations the default path calls.
 */
public interface NavigationSectionStages {

    /** Runs the host prefix and returns the profiler it opened for the successor. */
    ProfilerFiller prts$serverAiStepPrefix();

    /** Runs the host successor on the profiler the prefix opened. */
    void prts$serverAiStepSuccessor(ProfilerFiller profiler);
}
