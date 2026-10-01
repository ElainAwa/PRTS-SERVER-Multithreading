/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The collision walk of a moving entity: every step of the walk may reach a block into a chunk that
 * is not in memory yet, so the step is where the wait shows up.
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
