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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers a shorter lifetime (three seconds) for a projectile whose stored value is still the
 * mod's default; a pack that set a lifetime in its data files keeps it. Off by default, because
 * a shorter lifetime changes what the mod does rather than repairing a fault.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.data.gun.DefaultGunData", remap = false)
public abstract class PrtsSbwProjectileLifeMixin {

    @Shadow(remap = false)
    private int projectileLife;

    @Inject(method = "getProjectileLife", at = @At("HEAD"), cancellable = true, remap = false)
    private void prts$shortenProjectileLife(CallbackInfoReturnable<Integer> cir) {
        if (!PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "shorten-sbw-projectile-life")) {
            return;
        }
        if (this.projectileLife != PrtsSbwCompat.PROJECTILE_LIFE_DEFAULT) {
            return;
        }
        PrtsModSupportStats.count("sbw-projectile-life-answered");
        cir.setReturnValue(PrtsSbwCompat.PROJECTILE_LIFE_TICKS);
    }
}
