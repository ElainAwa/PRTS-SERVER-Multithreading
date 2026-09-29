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
 * Narrows the login refresh of the optional recipe mod to the player who logged in.
 *
 * <p>The mod reacts to a login by refreshing the recipe state of every player online, and every
 * client answers that with a full rebuild of its recipe and search index, which freezes that client
 * for seconds. The login only changed the state of the player who joined, so this handler cancels
 * the broadcast of the mod and sends the same two packets - the recipe packet and the payload that
 * carries the lock state - to that player. What those packets are is decided in {@link
 * PrtsUnlockableRecipesCompat}, which builds them the way the mod builds them.</p>
 *
 * <p>The handler runs where the mod's own login handler runs, on the server thread, so the refresh
 * goes out at the point the mod would have sent it from. A payload factory whose signature moved
 * makes the helper report failure, and the handler then leaves the mod its own broadcast: a refresh
 * that is not narrowed is better than one that is incomplete.</p>
 *
 * <p>The platform event class this handler receives is only available in this module, which is why
 * the member lives here and not next to the rest of the mod interoperability layer. The category
 * switch is still consulted at run time, so turning the category off leaves the mod alone as well.
 * The switch is on by default: what it prevents is other players freezing, not a difference in how
 * much work the mod does for the player who joined.</p>
 */
@LoadIfMod(modid = "unlockable_recipes", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.zanghero.unlockablerecipes.UnlockableRecipes", remap = false)
public abstract class PrtsUnlockableRecipesLoginSyncMixin {

    /**
     * Refreshes the recipe state of the joining player only.
     *
     * @param event the platform's login event
     * @param ci    callback handle
     */
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
