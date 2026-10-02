/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight, commit 19f6536e3ac419c40d1b857b924bdd5c2b188f24; see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.modsupport.plugin;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsBungeeChatPreload;
import org.bukkit.craftbukkit.v.CraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resolves Bungee chat classes before plugin class loaders exist, so none holds a second copy. */
@Mixin(value = CraftServer.class, remap = false)
public abstract class PrtsCraftServerPluginLoadMixin {

    @Inject(method = "loadPlugins", at = @At("HEAD"))
    private void prts$preloadBungeeChat(CallbackInfo ci) {
        if (PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "preload-bungee-chat-classes", true)) {
            PrtsBungeeChatPreload.preload();
        }
    }
}
