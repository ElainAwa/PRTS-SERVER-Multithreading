/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.concurrent.atomic.LongAdder;

/** Internal cold-load counter for a synchronous chunk read: only a main-thread getChunk with
 * load=true while the chunk is not yet visible counts. It is not published as a readout key until a
 * controlled window proves a positive count; async futures are not counted. */
public final class UnloadedReadReadings {

    private static final LongAdder COLD_LOADS = new LongAdder();

    private UnloadedReadReadings() {
    }

    public static boolean shouldCount(boolean load, boolean mainThread, boolean alreadyLoaded) {
        return load && mainThread && !alreadyLoaded;
    }

    public static void noteColdLoad() {
        COLD_LOADS.increment();
    }

    public static long coldLoads() {
        return COLD_LOADS.sum();
    }

    public static void reset() {
        COLD_LOADS.reset();
    }
}
