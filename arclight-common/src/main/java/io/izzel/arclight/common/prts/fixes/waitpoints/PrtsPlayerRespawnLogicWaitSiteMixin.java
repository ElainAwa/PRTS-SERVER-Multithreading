/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the respawn position search, whose spiral may reach a chunk not in memory. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(PlayerRespawnLogic.class)
public abstract class PrtsPlayerRespawnLogicWaitSiteMixin {

    @Inject(method = "getOverworldRespawnPos(Lnet/minecraft/server/level/ServerLevel;II)"
        + "Lnet/minecraft/core/BlockPos;", at = @At("HEAD"))
    private static void prts$openGetOverworldRespawnPos(ServerLevel level, int x, int z,
                                                        CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.PLAYER_RESPAWN_LOGIC_GET_OVERWORLD_RESPAWN_POS);
    }

    @Inject(method = "getOverworldRespawnPos(Lnet/minecraft/server/level/ServerLevel;II)"
        + "Lnet/minecraft/core/BlockPos;", at = @At("RETURN"))
    private static void prts$closeGetOverworldRespawnPos(ServerLevel level, int x, int z,
                                                         CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.end(PrtsWaitSites.PLAYER_RESPAWN_LOGIC_GET_OVERWORLD_RESPAWN_POS, level);
    }
}
