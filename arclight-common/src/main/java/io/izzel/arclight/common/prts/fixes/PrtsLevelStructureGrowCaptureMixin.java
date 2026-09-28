/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The write side of the tree capture: while a sapling grows, a block write is answered from the
 * capture list and never reaches the chunk. The check is one identity compare on the level that
 * started the grow, and it is false for every other write - block edits of players, ticks and
 * worldgen included - so the hot path only pays for the field read.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Level.class)
public abstract class PrtsLevelStructureGrowCaptureMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        cancellable = true, at = @At("HEAD"))
    private void prts$captureGrownTreeBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (PrtsStructureGrowCapture.capture((Level) (Object) this, pos, state, flags)) {
            // The generator is told the block is there; it is written only if the event allows it.
            cir.setReturnValue(true);
        }
    }

    /**
     * The read side of the same capture: a generator that clears the sapling and then asks whether
     * the spot is free must see its own write, otherwise the tree is built against a world where
     * the sapling is still standing and no tree is placed at all. The check is one field read for
     * every other block read on the server.
     */
    @Inject(method = "getBlockState", cancellable = true, at = @At("HEAD"))
    private void prts$readCapturedTreeBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState captured = PrtsStructureGrowCapture.capturedState((Level) (Object) this, pos);
        if (captured != null) {
            cir.setReturnValue(captured);
        }
    }
}
