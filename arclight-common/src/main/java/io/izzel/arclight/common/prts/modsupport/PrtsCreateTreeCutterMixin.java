/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

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
 * Hands the tree search of the machinery mod a reader that cannot leave the loaded world.
 *
 * <p>The reader is replaced at the head of the search, before the search captures it into its
 * worker lambdas, so every block lookup of that search - in the method body and in the lambda it
 * hands the reader to - goes through the same wrapper. See {@link PrtsTreeCutterGuard} for what
 * the wrapper does and which switches decide it.</p>
 *
 * <p>Only the entry point is touched: the search itself, its order and its result are the mod's
 * own, and a server without the mod never loads this class.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "create", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.saw.TreeCutter", remap = false)
public abstract class PrtsCreateTreeCutterMixin {

    /**
     * Replaces the reader of a tree search with the guarding wrapper.
     *
     * @param reader reader the search was called with
     * @return the reader the search runs with
     */
    @ModifyVariable(method = "findTree", at = @At("HEAD"), index = 0, argsOnly = true, remap = false)
    private static BlockGetter prts$guardTreeSearch(BlockGetter reader) {
        return PrtsTreeCutterGuard.guard(reader);
    }
}
