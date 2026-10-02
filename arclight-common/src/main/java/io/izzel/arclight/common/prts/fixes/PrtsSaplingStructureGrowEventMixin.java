/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Wraps the one call this platform grows a tree in, SaplingBlock#advanceTree -> TreeGrower; the tree
 * is built against the capture list and StructureGrowEvent decides whether it is written. Bone meal
 * and a random tick both end up here, so both raise the event exactly once.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(SaplingBlock.class)
public abstract class PrtsSaplingStructureGrowEventMixin {

    @Redirect(method = "advanceTree", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/grower/TreeGrower;growTree"
            + "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/util/RandomSource;)Z"))
    private boolean prts$growTreeWithEvent(TreeGrower grower, ServerLevel level, ChunkGenerator generator,
                                           BlockPos pos, BlockState state, RandomSource random) {
        return PrtsStructureGrowCapture.growWithEvent(grower, level, generator, pos, state, random);
    }
}
