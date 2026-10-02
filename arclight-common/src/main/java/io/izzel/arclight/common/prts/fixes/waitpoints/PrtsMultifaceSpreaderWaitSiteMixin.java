/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the block face spread of a growing block, which asks the world around the face. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceSpreader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(MultifaceSpreader.class)
public abstract class PrtsMultifaceSpreaderWaitSiteMixin {

    @Inject(method = "spreadToFace", at = @At("HEAD"))
    private void prts$openSpreadToFace(LevelAccessor level, MultifaceSpreader.SpreadPos pos,
                                       boolean spreadFromFace,
                                       CallbackInfoReturnable<Optional<MultifaceSpreader.SpreadPos>> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.MULTIFACE_SPREADER_SPREAD_TO_FACE);
    }

    @Inject(method = "spreadToFace", at = @At("RETURN"))
    private void prts$closeSpreadToFace(LevelAccessor level, MultifaceSpreader.SpreadPos pos,
                                        boolean spreadFromFace,
                                        CallbackInfoReturnable<Optional<MultifaceSpreader.SpreadPos>> cir) {
        PrtsWaitSites.end(PrtsWaitSites.MULTIFACE_SPREADER_SPREAD_TO_FACE, level);
    }
}
