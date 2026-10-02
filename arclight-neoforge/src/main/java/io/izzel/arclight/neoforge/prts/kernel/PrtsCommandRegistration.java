/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.config.PrtsCommand;
import io.izzel.arclight.common.prts.kernel.observe.KernelCommandExtension;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Publishes the PRTS command through the platform command event, which NeoForge fires on start and on
 * every data pack reload; the bridge is the only place that knows both sides.
 */
public final class PrtsCommandRegistration {

    private PrtsCommandRegistration() {
    }

    /** Registers the PRTS command for the dispatcher that was just built. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // Registration is idempotent across command rebuilds; this bridge knows both sides.
        PrtsCommand.registerExtension(KernelCommandExtension.extension());
        PrtsCommand.register(event.getDispatcher());
        PrtsCommand.registerInCommandMap();
    }
}
