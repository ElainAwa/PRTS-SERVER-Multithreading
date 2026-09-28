/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * A bone-mealed mushroom grows a huge mushroom through one ConfiguredFeature#place call, and the
 * event for it is raised here around the whole growMushroom call. The core capture injector sits at
 * that place call itself and reads the same block to name the species, so this mixin deliberately
 * wraps the caller instead of sharing the instruction - two injectors at one instruction is how a
 * redirect silently replaces another one on this platform, and the core injector has to keep
 * working.
 *
 * Capturing from before growMushroom also means the block it removes first is part of the capture:
 * a cancelled event leaves the mushroom standing, exactly like a cancelled sapling keeps its
 * sapling.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.TreeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(MushroomBlock.class)
public abstract class PrtsMushroomStructureGrowEventMixin {

    @Redirect(method = "performBonemeal", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/MushroomBlock;growMushroom"
            + "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)Z"))
    private boolean prts$growWithEvent(MushroomBlock block, ServerLevel level, BlockPos pos,
                                       BlockState state, RandomSource random) {
        return PrtsStructureGrowCapture.growWithEvent(level, pos, prts$species(),
            () -> block.growMushroom(level, pos, state, random));
    }

    /**
     * The species of the mushroom, which is the block itself - a modded mushroom block is not one
     * of the two and grows without an event, as it does today.
     */
    @SuppressWarnings("ConstantConditions")
    private TreeType prts$species() {
        if ((Object) this == Blocks.BROWN_MUSHROOM) {
            return TreeType.BROWN_MUSHROOM;
        }
        if ((Object) this == Blocks.RED_MUSHROOM) {
            return TreeType.RED_MUSHROOM;
        }
        return null;
    }
}
