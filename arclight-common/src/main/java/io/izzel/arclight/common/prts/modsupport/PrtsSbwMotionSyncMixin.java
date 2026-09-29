/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSbwCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spreads the motion sync of fast projectiles over several ticks instead of every tick.
 *
 * <p>The interval question lives in a shared default implementation, but the two projectile base
 * classes answer it with their own method that only delegates to that implementation, so the answer
 * has to be raised where a call is actually received: in those two methods. Subclasses inherit
 * either one of them, which is every fast projectile of the mod. A future class that answers the
 * question itself keeps its own answer, and one that delegates is covered by the same two methods
 * only if it extends one of these bases.</p>
 *
 * <p>Nothing is captured from the target: the handler only replaces the returned value, which keeps
 * the mod's own types off the compile class path entirely. The class is only applied while the mod
 * is present, so a server without it neither loads this class nor changes anything.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = {
    "com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity",
    "com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile"
}, remap = false)
public abstract class PrtsSbwMotionSyncMixin {

    /**
     * Answers the interval question of a projectile that delegates it to the shared default.
     *
     * @param cir callback handle carrying the answer
     */
    @Inject(method = "syncMotionInterval()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void prts$spreadMotionSync(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(PrtsSbwCompat.MOTION_SYNC_INTERVAL);
    }
}
