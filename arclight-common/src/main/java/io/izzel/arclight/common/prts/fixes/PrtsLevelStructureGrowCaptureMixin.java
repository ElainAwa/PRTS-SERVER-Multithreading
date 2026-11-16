/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The real level write entry: every setBlock attempt is judged by the write guard in place, and the
 * deferred carrier is materialized only if a routed write actually needs the commit path. The same
 * entry keeps the sapling capture's own-write read.
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
            PrtsWorldWriteTaps.clearJudgedWrite();
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
        PrtsWorldWriteTaps.Decision decision = PrtsWorldWriteTaps.beginBlockWrite(level,
            pos.getX(), pos.getY(), pos.getZ());
        if (!decision.judge()) {
            return true;
        }
        return decision.admitLazy(level, level.dimension().location().toString(),
            () -> new PrtsDeferredWrites.LevelWrite(level, pos, state, flags, recursionLeft));
    }

    /** Reports what the judged write actually did: a capture that only remembers the block and a
     * failed call both report no landing, so admissions are not counted as published writes. */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN"))
    private void prts$reportWorldWriteResult(BlockPos pos, BlockState state, int flags,
                                             int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Object judged = PrtsWorldWriteTaps.judgedWrite();
        if (judged == null) {
            return;
        }
        PrtsWorldWriteTaps.clearJudgedWrite();
        Level level = (Level) (Object) this;
        // While the tree is being built the level answers a captured block from the capture list, so a
        // read back equal to the requested state only proves the capture, not the world. The capture
        // stage is a staging write, never a landing; the real dispatch call reports itself later.
        if (PrtsStructureGrowCapture.capturedState(level, pos) != null) {
            PrtsWorldWriteTaps.completeBlockWrite(level, pos.getX(), pos.getY(), pos.getZ(), null,
                false);
            return;
        }
        BlockState read = level.getBlockState(pos);
        boolean applied = Boolean.TRUE.equals(cir.getReturnValue()) && read == state;
        PrtsWorldWriteTaps.completeBlockWrite(level, pos.getX(), pos.getY(), pos.getZ(), read, applied);
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
