/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the gateway teleport search and the chunk emptiness check it lands on. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(TheEndGatewayBlockEntity.class)
public abstract class PrtsEndGatewayWaitSitesMixin {

    @Inject(method = "findOrCreateValidTeleportPos", at = @At("HEAD"))
    private static void prts$openFindOrCreateValidTeleportPos(ServerLevel level, BlockPos pos,
                                                              CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.END_GATEWAY_FIND_TELEPORT_POS);
    }

    @Inject(method = "findOrCreateValidTeleportPos", at = @At("RETURN"))
    private static void prts$closeFindOrCreateValidTeleportPos(ServerLevel level, BlockPos pos,
                                                               CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.end(PrtsWaitSites.END_GATEWAY_FIND_TELEPORT_POS, level);
    }

    @Inject(method = "isChunkEmpty", at = @At("HEAD"))
    private static void prts$openIsChunkEmpty(ServerLevel level, Vec3 pos,
                                              CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.END_GATEWAY_IS_CHUNK_EMPTY);
    }

    @Inject(method = "isChunkEmpty", at = @At("RETURN"))
    private static void prts$closeIsChunkEmpty(ServerLevel level, Vec3 pos,
                                               CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.end(PrtsWaitSites.END_GATEWAY_IS_CHUNK_EMPTY, level);
    }
}
