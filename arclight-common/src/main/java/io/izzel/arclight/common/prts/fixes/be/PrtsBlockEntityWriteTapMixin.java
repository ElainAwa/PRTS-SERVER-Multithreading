/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The narrow write-entry tap for block entities: off it does nothing, on it asks the write gate.
 * Minecraft's block-entity change/removal methods are wrapped at HEAD and cancelled only when the
 * enabled gate refuses, so the default path keeps the vanilla call and its ordering. */
package io.izzel.arclight.common.prts.fixes.be;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.sites.BlockEntityWriteTap;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(BlockEntity.class)
public abstract class PrtsBlockEntityWriteTapMixin {

    @Inject(method = "setChanged", at = @At("HEAD"), cancellable = true)
    private void prts$writeTapChanged(CallbackInfo ci) {
        if (!BlockEntityWriteTap.INSTANCE.allows(
            new io.izzel.arclight.common.prts.kernel.sites.WriteTapContext(
                "", null, 0L, 0L, "block-entity", "block-entity"), null)) {
            ci.cancel();
        }
    }

    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void prts$writeTapRemoved(CallbackInfo ci) {
        if (!BlockEntityWriteTap.INSTANCE.allows(
            new io.izzel.arclight.common.prts.kernel.sites.WriteTapContext(
                "", null, 0L, 0L, "block-entity", "block-entity"), null)) {
            ci.cancel();
        }
    }
}
