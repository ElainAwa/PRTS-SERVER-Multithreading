/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/**
 * The seam the platform reports its tick boundary through. A sampler can only tell a wait inside a
 * tick from the wait between two ticks if it is told where the boundary is, and this seam is that
 * one bit: entered when the tick body starts, left when it ends. Both calls are no-ops while no
 * probe is installed, so the seam costs one volatile read on a path the platform already runs.
 */
public final class PrtsLoadProbe {

    public interface Probe {

        void tickEntered();

        void tickLeft();
    }

    private static volatile Probe probe;

    private PrtsLoadProbe() {
    }

    public static void install(Probe watcher) {
        probe = watcher;
    }

    public static boolean installed() {
        return probe != null;
    }

    public static Probe watcher() {
        return probe;
    }

    public static void tickEntered() {
        Probe watcher = probe;
        if (watcher != null) {
            watcher.tickEntered();
        }
    }

    public static void tickLeft() {
        Probe watcher = probe;
        if (watcher != null) {
            watcher.tickLeft();
        }
    }
}
