/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Regression asset, not part of the server: a third party mixin that anchors on the totem cure call
 * site. Re-run it by copying this class and the configuration next to it into the main source set,
 * registering the configuration from the mixin connector, and starting the server; an injection
 * failure means the call site is no longer an instruction of the totem death protection method, so
 * injectors of third party mods can no longer find it.
 */
package io.izzel.arclight.common.prts.probe;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PrtsAnchorProbeMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;removeEffectsCuredBy"
            + "(Lnet/neoforged/neoforge/common/EffectCure;)Z"))
    private void prts$probe(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
    }
}
