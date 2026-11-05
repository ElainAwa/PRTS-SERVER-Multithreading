/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;

/** One complete RCON command unit as EVENT work in the existing self timer. */
public final class RconEventMeter {

    /** The site reference carried beside the event class. */
    public static final String SITE = "rcon-command";

    private RconEventMeter() {
    }

    /** Records one unit when the kernel, share table and degrade actions are all on. */
    public static void note(String worldId, long startNanos) {
        note(worldId, startNanos, KernelSettings.enabled() && KernelSettings.shareTable()
            && KernelSettings.degradeActions());
    }

    static void note(String worldId, long startNanos, boolean allowed) {
        if (!allowed || worldId == null || worldId.isEmpty() || startNanos <= 0L) {
            return;
        }
        long nanos = System.nanoTime() - startNanos;
        if (nanos > 0L) {
            SelfTimers.note(SelfClass.EVENT, worldId, SITE, nanos);
        }
    }
}
