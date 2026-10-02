/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the collision walk of a moving entity, whose steps may reach a chunk not in memory. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.world.level.BlockCollisions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(BlockCollisions.class)
public abstract class PrtsBlockCollisionsWaitSiteMixin {

    @Inject(method = "computeNext", at = @At("HEAD"))
    private void prts$openComputeNext(CallbackInfoReturnable<Object> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.BLOCK_COLLISIONS_COMPUTE_NEXT);
    }

    @Inject(method = "computeNext", at = @At("RETURN"))
    private void prts$closeComputeNext(CallbackInfoReturnable<Object> cir) {
        PrtsWaitSites.end(PrtsWaitSites.BLOCK_COLLISIONS_COMPUTE_NEXT);
    }
}
