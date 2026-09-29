/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

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
 * Answers a shorter default lifetime for the projectiles of the optional vehicle mod.
 *
 * <p>That mod keeps a projectile alive for 400 ticks unless its own data files say otherwise. At
 * the fire rates of its fast weapons one player can have hundreds of them in flight and every one
 * of them pays a full hit check per tick, so a full server carries tens of thousands of them. The
 * answer here is 60 ticks (three seconds) and it is given only while the stored value is still the
 * default: a pack that set a lifetime in its data files keeps it, so the data driven behaviour of
 * the mod is untouched.</p>
 *
 * <p>The switch is off by default, because a shorter lifetime is a change in what the mod does and
 * not a fault being repaired. Nothing here is compiled against the mod: the field is read through a
 * shadow declaration, and a server without the mod never loads this class.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = PrtsSbwCompat.MOD_ID, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.data.gun.DefaultGunData", remap = false)
public abstract class PrtsSbwProjectileLifeMixin {

    /** Stored lifetime of the mod; the answer below only replaces this value. */
    @Shadow(remap = false)
    private int projectileLife;

    /**
     * Answers the shortened lifetime while the stored value is the default of the mod.
     *
     * @param cir callback handle carrying the answer
     */
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
