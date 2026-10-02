/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.create;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsTreeCutterGuard;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hands the tree search a reader that cannot leave the loaded world; the reader is replaced at
 * the head of the search, before it is captured into the mod's worker lambdas. The search
 * itself, its order and its result stay the mod's own.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.saw.TreeCutter", remap = false)
public abstract class PrtsCreateTreeCutterMixin {

    @ModifyVariable(method = "findTree", at = @At("HEAD"), index = 0, argsOnly = true, remap = false)
    private static BlockGetter prts$guardTreeSearch(BlockGetter reader) {
        return PrtsTreeCutterGuard.guard(reader);
    }
}
