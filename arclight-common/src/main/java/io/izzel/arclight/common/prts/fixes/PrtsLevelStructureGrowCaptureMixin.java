/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The write and read sides of the tree capture: while a sapling grows, a write is answered from the
 * capture list so the generator sees its own write; false for every other level write and block read.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsDeferredWrites;
import io.izzel.arclight.common.prts.support.PrtsStructureGrowCapture;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
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
        if (!prts$admitWorldWrite(pos, state, flags, recursionLeft)) {
            cir.setReturnValue(false);
            return;
        }
        if (PrtsStructureGrowCapture.capture((Level) (Object) this, pos, state, flags)) {
            // The generator is told the block is there; it is written only if the event allows it.
            cir.setReturnValue(true);
        }
    }

    private boolean prts$admitWorldWrite(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        Level level = (Level) (Object) this;
        PrtsWorldWriteTaps.Decision decision = PrtsWorldWriteTaps.beginBlockWrite(level);
        if (!decision.judge()) {
            return true;
        }
        return decision.admit(level, level.dimension().location().toString(),
            new PrtsDeferredWrites.LevelWrite(level, pos, state, flags, recursionLeft));
    }

    /** The read side of the capture: only the level that started the grow answers from the capture
     * list, so every other block read pays one field read. */
    @Inject(method = "getBlockState", cancellable = true, at = @At("HEAD"))
    private void prts$readCapturedTreeBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState captured = PrtsStructureGrowCapture.capturedState((Level) (Object) this, pos);
        if (captured != null) {
            cir.setReturnValue(captured);
        }
    }
}
