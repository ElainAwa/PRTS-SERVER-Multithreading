/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.config.PrtsReloadCommand;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.command.CraftCommandMap;
import org.bukkit.plugin.PluginLoadOrder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes {@code /prts reload} once the Bukkit command map is complete.
 *
 * <p>Registration happens after {@code enablePlugins(POSTWORLD)} and is followed by an explicit
 * {@code syncCommands()}: the dispatcher that the console and players actually use is rebuilt from
 * the Bukkit command map at that point, so registering later without a re-sync would leave the
 * command reachable through the Bukkit API only.</p>
 *
 * <p>PRTS category: fixes (shared infrastructure). It touches no kernel seam.</p>
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsReloadCommandMixin {

    private static final Logger PRTS_LOGGER = ArclightI18nLogger.getLogger("Arclight");

    @Shadow @Final private CraftCommandMap commandMap;

    @Shadow
    public abstract void syncCommands();

    @Inject(method = "enablePlugins", remap = false, at = @At("RETURN"))
    private void prts$registerReloadCommand(PluginLoadOrder type, CallbackInfo ci) {
        if (type != PluginLoadOrder.POSTWORLD) {
            return;
        }
        try {
            this.commandMap.register("prts", new PrtsReloadCommand());
            this.syncCommands();
            PRTS_LOGGER.info("prts-config: /prts reload registered");
        } catch (Throwable throwable) {
            // a failed registration must not break server start; the file-based switches still work
            PRTS_LOGGER.warn("prts-config: could not register /prts reload", throwable);
        }
    }
}
