/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.ai.navigation.PathNavigation;

import java.util.concurrent.atomic.LongAdder;

/**
 * The navigation span stage: the original {@code PathNavigation.tick()} body block is called through
 * this one entry, so the original host method and any later owner strategy share one implementation
 * of that block instead of keeping two algorithms. This first step runs the same virtual call on the
 * calling thread; it carries no permission, no deferral and no write of its own, and the host keeps
 * its profiler push/pop, locals and tail around this call unchanged.
 */
public final class NavigationSpanStage {

    private static final LongAdder RUNS = new LongAdder();

    private NavigationSpanStage() {
    }

    /** Runs the extracted navigation block; the caller keeps its own frame and profiler state. */
    public static void run(PathNavigation navigation) {
        RUNS.increment();
        if (OwnerNavigationWorker.run(navigation)) {
            return;
        }
        navigation.tick();
    }

    /** How many times the extracted block ran; observation only. */
    public static long runs() {
        return RUNS.sum();
    }

    /** Test-only reset of the observation counter. */
    public static void resetForTests() {
        RUNS.reset();
    }
}