/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spreads the motion sync of fast projectiles over several ticks instead of every tick.
 *
 * <p>The mod keeps the interval in a shared default implementation that every projectile delegates
 * to, so raising the value there reaches all of them; a projectile that answers the question itself
 * keeps its own answer. The callers already throttle on the returned interval, so nothing else
 * changes - only how often the same message goes out.</p>
 *
 * <p>The class is only applied while the mod is present, and it refers to the mod by name only, so
 * a server without the mod neither loads it nor pays for the reflection below.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.entity.projectile.IFastMotionSync$DefaultImpls", remap = false)
public abstract class PrtsSbwMotionSyncMixin {

    /**
     * Answers the interval question of the shared default implementation.
     *
     * @param projectile the projectile asking, typed as object because the mod type is not on the
     *     compile class path
     * @param cir callback handle carrying the answer
     */
    @Inject(method = "syncMotionInterval(Lcom/atsuishio/superbwarfare/entity/projectile/IFastMotionSync;)I",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void prts$spreadMotionSync(Object projectile, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(PrtsSbwCompat.MOTION_SYNC_INTERVAL);
    }
}
