/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/** The run-scoped count of full graph builds started without a work budget. */
public final class RecomputeUnbounded {

    private static final AtomicLong generation = new AtomicLong();
    private static final AtomicLong count = new AtomicLong();
    private static volatile boolean covered;

    private RecomputeUnbounded() {
    }

    /** Starts a new generation: clears the count and the coverage claim. */
    public static void reset() {
        count.set(0L);
        covered = false;
        generation.incrementAndGet();
    }

    /** Records one full build that started without a work budget. */
    public static void noteUnbudgetedStart() {
        count.incrementAndGet();
        covered = true;
    }

    /** Makes the counter source covered after a budgeted full build. */
    public static void noteBudgetedBuild() {
        covered = true;
    }

    /** The current generation count, empty while no graph build covered the source. */
    public static OptionalLong reading() {
        return covered ? OptionalLong.of(count.get()) : OptionalLong.empty();
    }

    static long generation() {
        return generation.get();
    }
}
