/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Hooks the platform block write path: the same question the level-side hook asks, cancelling
 * only when the decision refuses. A routed write is handed over as the same setter call and runs
 * on the tick thread, where this hook takes the short path, so it cannot be handed over twice.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsDeferredWrites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v.block.CraftBlock;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(value = CraftBlock.class, remap = false)
public abstract class PrtsCraftBlockWriteTapMixin {

    // @formatter:off
    @Shadow public abstract CraftWorld getCraftWorld();
    // @formatter:on

    @Inject(method = "setBlockData(Lorg/bukkit/block/data/BlockData;Z)V", at = @At("HEAD"),
        cancellable = true)
    private void prts$admitPlatformBlockWrite(BlockData data, boolean applyPhysics, CallbackInfo ci) {
        ServerLevel level = getCraftWorld().getHandle();
        PrtsWorldWriteTaps.Decision decision = PrtsWorldWriteTaps.beginBlockWrite(level);
        if (!decision.judge()) {
            return;
        }
        if (!decision.admit(level, level.dimension().location().toString(),
            new PrtsDeferredWrites.PlatformWrite((CraftBlock) (Object) this, data, applyPhysics))) {
            ci.cancel();
        }
    }
}
