/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times every ticker of the block entity list of one level and reports the instance behind it, so a
 * stall inside this list can be attributed to a type and a mod. The ticks are called exactly as they
 * were and the spans are read around them; nothing is cancelled, delayed or reordered. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsBlockEntityCosts;
import io.izzel.arclight.common.prts.support.PrtsCpuClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Level.class)
public abstract class PrtsBlockEntityTickCostMixin {

    @Unique
    private long prts$segmentStartedAt;

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void prts$openSegment(CallbackInfo ci) {
        if (PrtsBlockEntityCosts.installed()) {
            prts$segmentStartedAt = System.nanoTime();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void prts$closeSegment(CallbackInfo ci) {
        if (!PrtsBlockEntityCosts.installed()) {
            return;
        }
        Level level = (Level) (Object) this;
        PrtsBlockEntityCosts.segmentTick(level.dimension().location().toString(),
            System.nanoTime() - prts$segmentStartedAt);
    }

    @WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void prts$timeBlockEntityTick(TickingBlockEntity ticker, Operation<Void> original) {
        if (!PrtsBlockEntityCosts.installed()) {
            original.call(ticker);
            return;
        }
        long startedAt = System.nanoTime();
        long cpuStartedAt = PrtsCpuClock.now();
        try {
            original.call(ticker);
        } finally {
            Level level = (Level) (Object) this;
            PrtsBlockEntityCosts.blockEntityTick(ticker, level,
                level.dimension().location().toString(),
                System.nanoTime() - startedAt, PrtsCpuClock.since(cpuStartedAt));
        }
    }
}
