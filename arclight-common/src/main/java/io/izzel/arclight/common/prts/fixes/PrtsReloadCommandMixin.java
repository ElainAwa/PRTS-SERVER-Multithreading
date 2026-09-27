/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.plugin.PluginLoadOrder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes {@code /prts reload} to the dispatcher the server console and players actually use.
 *
 * <p>The command is registered into the vanilla dispatcher after {@code enablePlugins(POSTWORLD)}:
 * that call rebuilds the dispatcher from the Bukkit command map, so registering anywhere earlier
 * would be discarded, and registering only in the Bukkit command map is not enough because console
 * input never goes through it.</p>
 *
 * <p>PRTS category: fixes (shared infrastructure). It touches no kernel seam.</p>
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsReloadCommandMixin {

    private static final Logger PRTS_LOGGER = ArclightI18nLogger.getLogger("Arclight");

    @Shadow @Final protected DedicatedServer console;

    @Inject(method = "enablePlugins", remap = false, at = @At("RETURN"))
    private void prts$registerReloadCommand(PluginLoadOrder type, CallbackInfo ci) {
        if (type != PluginLoadOrder.POSTWORLD) {
            return;
        }
        try {
            // The console and the remote console resolve commands through the vanilla dispatcher
            // kept by the server, while Bukkit commands are published to the resource dispatcher
            // by syncCommands(). Registering both keeps /prts reachable from every command source.
            this.console.resources.managers().commands.getDispatcher().register(prts$reloadCommand());
            Commands vanilla = ((MinecraftServerBridge) this.console).bridge$getVanillaCommands();
            if (vanilla != null) {
                vanilla.getDispatcher().register(prts$reloadCommand());
            }
            PRTS_LOGGER.info("prts-config: /prts reload registered");
        } catch (Throwable throwable) {
            // a failed registration must not break server start; file-based switches still work
            PRTS_LOGGER.warn("prts-config: could not register /prts reload", throwable);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> prts$reloadCommand() {
        return Commands.literal("prts")
            .then(Commands.literal("reload").executes(context -> {
                PrtsConfigManager.reload();
                context.getSource().sendSuccess(() -> Component.literal(
                    "[PRTS] prts-config reloaded: " + PrtsConfigManager.snapshot()), false);
                return 1;
            }));
    }
}
