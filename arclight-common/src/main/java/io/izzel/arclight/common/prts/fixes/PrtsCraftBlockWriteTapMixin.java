/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The platform side of the block write path: every write a plugin or an event performs through the
 * block API funnels through the data setter, so one hook covers all of them. The hook asks the same
 * question the level-side hook asks and cancels the write only when an enforced decision refuses
 * it; with enforcement off it only records, and the write proceeds untouched.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
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
        if (PrtsWorldWriteTaps.classifyBlockWrite(level) == PrtsWorldWriteTaps.BlockWriteTap.PASS) {
            return;
        }
        if (!PrtsWorldWriteTaps.admitBlockWrite(level, level.dimension().location().toString(), null)) {
            ci.cancel();
        }
    }
}
