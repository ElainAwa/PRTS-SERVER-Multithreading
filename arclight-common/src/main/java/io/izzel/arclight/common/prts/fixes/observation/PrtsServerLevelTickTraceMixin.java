/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.common.prts.kernel.AiSensorCounters;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class PrtsServerLevelTickTraceMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void prts$countLevelTick(CallbackInfo ci) {
        AiSensorCounters.levelTick();
    }
}
