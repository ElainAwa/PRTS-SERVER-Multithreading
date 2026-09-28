/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 19f6536e3ac419c40d1b857b924bdd5c2b188f24
 * ("add optional Bungee Chat preloading").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsBungeeChatPreload;
import org.bukkit.craftbukkit.v.CraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Resolves the Bungee chat classes before the plugin class loaders are built.
 *
 * <p>Plugins that send Bungee chat components need those classes to come from the server class
 * loader. Loading them here, at the moment plugin loading starts, keeps an isolated plugin class
 * loader from resolving a second copy of the API.</p>
 */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsCraftServerPluginLoadMixin {

    @Inject(method = "loadPlugins", at = @At("HEAD"))
    private void prts$preloadBungeeChat(CallbackInfo ci) {
        if (PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "preload-bungee-chat-classes", true)) {
            PrtsBungeeChatPreload.preload();
        }
    }
}
