/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsNativeReadings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts the Belt callback entry that really re-enters Java from the native payload. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.neoforge.physics.callback.BeltBlockCallback", remap = false)
public abstract class PrtsSableBeltCallbackMixin {

    @Inject(method = "sable$onCollision", at = @At("HEAD"), remap = false)
    private void prts$countBeltCallback(CallbackInfo ci) {
        PrtsNativeReadings.noteCallback();
    }
}
