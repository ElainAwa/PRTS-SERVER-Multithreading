/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.common.prts.kernel.AiSensorCounters;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestLivingEntitySensor.class)
public abstract class PrtsNearestLivingSensorTraceMixin {

    @Inject(method = "doTick", at = @At("HEAD"))
    private void prts$countNearestDoTick(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        AiSensorCounters.nearestLivingDoTick();
    }
}
