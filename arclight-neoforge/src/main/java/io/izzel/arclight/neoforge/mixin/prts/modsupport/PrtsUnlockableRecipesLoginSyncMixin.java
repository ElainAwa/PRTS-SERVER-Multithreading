/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.mixin.prts.modsupport;

import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.prts.PrtsSwitches;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
import io.izzel.arclight.common.prts.support.PrtsUnlockableRecipesCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Narrows the login refresh of the optional recipe mod to the player who logged in: the mod refreshes
 * every online player, and each client freezes for seconds rebuilding its recipe and search index.
 * It cancels the mod's broadcast and sends the same two packets through {@link
 * PrtsUnlockableRecipesCompat}; when the mod's factory cannot be read the handler leaves the mod its
 * own broadcast instead of sending an incomplete refresh.
 */
@LoadIfMod(modid = "unlockable_recipes", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.zanghero.unlockablerecipes.UnlockableRecipes", remap = false)
public abstract class PrtsUnlockableRecipesLoginSyncMixin {

    @Inject(method = "onPlayerLogin", at = @At("HEAD"), cancellable = true, remap = false)
    private void prts$syncRecipeStateOnlyToJoiner(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        if (!PrtsSwitches.enabled(PrtsSwitches.MODSUPPORT)
                || !PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT,
                    "narrow-unlockable-recipes-login-sync")) {
            return;
        }
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!PrtsUnlockableRecipesCompat.syncToJoiner(server, serverPlayer, this)) {
            return;
        }
        int online = server.getPlayerList().getPlayers().size();
        PrtsModSupportStats.count("unlockable-recipes-logins-narrowed");
        PrtsModSupportStats.count("unlockable-recipes-recipients-skipped", Math.max(0, online - 1));
        ci.cancel();
    }
}
