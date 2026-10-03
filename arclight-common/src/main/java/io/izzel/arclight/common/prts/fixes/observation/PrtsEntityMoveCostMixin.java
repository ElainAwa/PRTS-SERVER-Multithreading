/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the movement segment of one row: the collision answer and the displacement it produces. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Entity.class)
public abstract class PrtsEntityMoveCostMixin {

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"))
    private void prts$openEntityMove(MoverType type, Vec3 motion, CallbackInfo ci) {
        PrtsEntityCapability.beginMove((Entity) (Object) this);
    }

    @Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("RETURN"))
    private void prts$closeEntityMove(MoverType type, Vec3 motion, CallbackInfo ci) {
        PrtsEntityCapability.endMove((Entity) (Object) this);
    }
}
