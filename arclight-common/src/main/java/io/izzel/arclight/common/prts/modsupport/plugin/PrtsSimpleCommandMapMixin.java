/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 381ee59959b966e43a6685f85e79855c73e927f9
 * ("control Bukkit reload commands").
 * Reworked as a standalone mixin of the prts.modsupport category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.modsupport.plugin;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Removes the Bukkit reload commands when the operator asks for it.
 *
 * <p>A Bukkit reload re-enters plugin loading without restarting the process, which leaves the
 * hybrid server in a state that neither the platform nor the installed mods expect. The switch
 * defaults to keeping the commands: removing them is a deliberate operator decision, and the
 * removal happens as the command map installs its defaults, so a later registration cannot bring
 * the label back.</p>
 */
@Mixin(value = SimpleCommandMap.class, remap = false)
public abstract class PrtsSimpleCommandMapMixin {

    @Shadow
    protected Map<String, Command> knownCommands;

    @Inject(method = "setDefaultCommands", at = @At("TAIL"))
    private void prts$dropReloadCommands(CallbackInfo ci) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "disable-bukkit-reload-command", false)) {
            return;
        }
        Command command = this.knownCommands.remove("reload");
        this.knownCommands.remove("bukkit:reload");
        if (command != null) {
            command.unregister((CommandMap) (Object) this);
        }
    }
}
