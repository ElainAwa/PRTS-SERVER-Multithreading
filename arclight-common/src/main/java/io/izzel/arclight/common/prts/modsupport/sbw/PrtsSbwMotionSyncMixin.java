/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sbw;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsModSupportStats;
import io.izzel.arclight.common.prts.support.PrtsSbwCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spreads the motion sync of fast projectiles over several ticks. The interval question is
 * answered in the two projectile base classes, which is where a call is actually received; a
 * subclass that answers it itself keeps its own answer.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = {
    "com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity",
    "com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile"
}, remap = false)
public abstract class PrtsSbwMotionSyncMixin {

    @Inject(method = "syncMotionInterval()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void prts$spreadMotionSync(CallbackInfoReturnable<Integer> cir) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "spread-sbw-motion-sync")) {
            return;
        }
        PrtsModSupportStats.count("sbw-motion-sync-answered");
        cir.setReturnValue(PrtsSbwCompat.MOTION_SYNC_INTERVAL);
    }
}
