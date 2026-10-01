/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The point of interest scan around a position: it walks a square of chunks and therefore needs
 * every one of them in memory.
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
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(PoiManager.class)
public abstract class PrtsPoiManagerWaitSiteMixin {

    @Inject(method = "ensureLoadedAndValid", at = @At("HEAD"))
    private void prts$openEnsureLoadedAndValid(LevelReader level, BlockPos pos, int radius,
                                               CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.POI_MANAGER_ENSURE_LOADED_AND_VALID);
    }

    @Inject(method = "ensureLoadedAndValid", at = @At("RETURN"))
    private void prts$closeEnsureLoadedAndValid(LevelReader level, BlockPos pos, int radius,
                                                CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.POI_MANAGER_ENSURE_LOADED_AND_VALID, level);
    }
}
