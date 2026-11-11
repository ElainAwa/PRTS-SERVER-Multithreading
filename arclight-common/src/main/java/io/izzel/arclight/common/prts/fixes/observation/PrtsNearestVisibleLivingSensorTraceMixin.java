/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.common.prts.kernel.AiSensorCounters;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestVisibleLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestVisibleLivingEntitySensor.class)
public abstract class PrtsNearestVisibleLivingSensorTraceMixin {

    @Inject(method = "doTick", at = @At("HEAD"))
    private void prts$countHostilesDoTick(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        if ((Object) this instanceof VillagerHostilesSensor) {
            AiSensorCounters.villagerHostilesDoTick();
        }
    }
}
