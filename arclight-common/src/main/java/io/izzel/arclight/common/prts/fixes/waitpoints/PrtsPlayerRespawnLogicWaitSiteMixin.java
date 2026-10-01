/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The respawn position search: it spirals outwards until it finds a spot that is good enough, and
 * every step of the spiral may reach a chunk that is not in memory.
 *
 * The hook opens an observation at the head of the method and closes it at the return, and that is
 * all it does: no upper bound is read into a decision, no wait is shortened, delayed or cancelled,
 * and the call proceeds exactly as it did before. With no watcher installed both calls are a single
 * volatile read each.
 */
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
