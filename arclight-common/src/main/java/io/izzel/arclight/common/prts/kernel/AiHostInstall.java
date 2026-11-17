/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import net.minecraft.server.MinecraftServer;

/** Retired side path: the program-shaped entity work replaced this worker handoff, so no pool,
 * no capture and no wait is built; arclight.prts.aiPlan stays default off. */
@Deprecated
final class AiHostInstall {

    static final String PROPERTY = "arclight.prts.aiPlan";

    private AiHostInstall() {
    }

    static synchronized void noteServer(MinecraftServer server) {
    }

    static synchronized void stop() {
    }
}
