/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the host tick of one row, the whole cost a kinematics owner would have to replace. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerLevel.class)
public abstract class PrtsEntityTickCostMixin {

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void prts$openEntityTick(Entity entity, CallbackInfo ci) {
        PrtsEntityCapability.beginTick(entity);
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void prts$closeEntityTick(Entity entity, CallbackInfo ci) {
        PrtsEntityCapability.endTick(entity);
    }
}
