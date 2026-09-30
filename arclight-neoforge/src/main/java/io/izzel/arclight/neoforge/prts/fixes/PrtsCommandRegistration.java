/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.fixes;

import io.izzel.arclight.common.prts.config.PrtsCommand;
import io.izzel.arclight.common.prts.kernel.observe.KernelCommandExtension;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Publishes the PRTS command through the platform command event.
 *
 * <p>NeoForge fires {@link RegisterCommandsEvent} from the {@code Commands} constructor, so the
 * listener runs again whenever the server rebuilds its command dispatcher (start and every data
 * pack reload). The shared command is registered into that dispatcher and into the Bukkit command
 * map, which keeps it reachable from the console and from players.</p>
 *
 * <p>PRTS category: fixes, NeoForge platform module.</p>
 */
public final class PrtsCommandRegistration {

    private PrtsCommandRegistration() {
    }

    /**
     * Registers the PRTS command for the dispatcher that was just built.
     *
     * @param event platform event carrying the fresh dispatcher
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // The kernel readout is registered here rather than inside the configuration package: the
        // command layer owns the extension point, the kernel owns the extension, and the bridge is
        // the only place that knows both. Registration is idempotent across command rebuilds.
        PrtsCommand.registerExtension(KernelCommandExtension.extension());
        PrtsCommand.register(event.getDispatcher());
        PrtsCommand.registerInCommandMap();
    }
}
