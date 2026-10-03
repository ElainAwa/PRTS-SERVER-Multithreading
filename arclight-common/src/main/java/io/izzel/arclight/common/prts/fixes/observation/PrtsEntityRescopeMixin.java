/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Counts the rows the host entity entry offers and the rows that reach its tick, and times that
 * tick: the invoke-shift injections sit on the host call of Entity#tick, the point a takeover would
 * decide on, so the counts and the durations share one denominator. Read-only and off by default.
 */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsEntityRescope;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerLevel.class)
public abstract class PrtsEntityRescopeMixin {

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void prts$offerRow(Entity entity, CallbackInfo ci) {
        PrtsEntityRescope.rowOffered(entity);
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V",
            shift = At.Shift.BEFORE))
    private void prts$enterHostTick(Entity entity, CallbackInfo ci) {
        PrtsEntityRescope.hostEntry((ServerLevel) (Object) this, entity);
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V",
            shift = At.Shift.AFTER))
    private void prts$leaveHostTick(Entity entity, CallbackInfo ci) {
        PrtsEntityRescope.hostExit(entity);
    }

    @Inject(method = "tickPassenger(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"))
    private void prts$passengerRow(Entity vehicle, Entity passenger, CallbackInfo ci) {
        PrtsEntityRescope.passengerRow(passenger);
    }
}
