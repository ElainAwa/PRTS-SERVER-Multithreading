/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the point of interest scan, which walks a square of chunks around the position. */
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
