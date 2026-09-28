/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The Bukkit species of a tree (TreeType) is decided by the configured feature the grower resolved,
 * not by the sapling block: a 2x2 spruce and a single one are the same block but a mega redwood and
 * a redwood to Bukkit. Both resolutions happen inside TreeGrower#growTree before it places
 * anything, so their return values are read here while a grow is being captured.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(TreeGrower.class)
public abstract class PrtsTreeGrowerSpeciesMixin {

    @Inject(method = "getConfiguredFeature", at = @At("RETURN"))
    private void prts$recordSpecies(RandomSource random, boolean hasFlowers,
                                    CallbackInfoReturnable<ResourceKey<ConfiguredFeature<?, ?>>> cir) {
        PrtsStructureGrowCapture.recordSpecies(cir.getReturnValue());
    }

    @Inject(method = "getConfiguredMegaFeature", at = @At("RETURN"))
    private void prts$recordMegaSpecies(RandomSource random,
                                        CallbackInfoReturnable<ResourceKey<ConfiguredFeature<?, ?>>> cir) {
        PrtsStructureGrowCapture.recordSpecies(cir.getReturnValue());
    }
}
