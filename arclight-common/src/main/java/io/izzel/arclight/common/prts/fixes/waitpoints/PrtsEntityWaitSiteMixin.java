/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The entity side of the wait observations: the position write every move funnels through, and the
 * spawn position adjust a joining entity runs.
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Entity.class)
public abstract class PrtsEntityWaitSiteMixin {

    @Inject(method = "setPosRaw(DDD)V", at = @At("HEAD"))
    private void prts$openSetPosRaw(double x, double y, double z, CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.ENTITY_SET_POS_RAW);
    }

    @Inject(method = "setPosRaw(DDD)V", at = @At("RETURN"))
    private void prts$closeSetPosRaw(double x, double y, double z, CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.ENTITY_SET_POS_RAW, ((Entity) (Object) this).level());
    }

    @Inject(method = "adjustSpawnLocation", at = @At("HEAD"))
    private void prts$openAdjustSpawnLocation(ServerLevel level, BlockPos pos,
                                              CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.ENTITY_ADJUST_SPAWN_LOCATION);
    }

    @Inject(method = "adjustSpawnLocation", at = @At("RETURN"))
    private void prts$closeAdjustSpawnLocation(ServerLevel level, BlockPos pos,
                                               CallbackInfoReturnable<BlockPos> cir) {
        PrtsWaitSites.end(PrtsWaitSites.ENTITY_ADJUST_SPAWN_LOCATION, level);
    }
}
