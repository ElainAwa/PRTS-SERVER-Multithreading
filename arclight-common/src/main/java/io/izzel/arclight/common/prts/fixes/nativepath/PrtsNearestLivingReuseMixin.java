/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeNeighborStage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Behaviour: when a wave staged this row's neighbour scan, the sensor writes that frozen result and
 * skips its own world scan. Why: the scan already ran as a parallel read node; repeating it pays
 * twice. Point: the doTick entry, before any scan. Invariant: only a bound row takes the staged
 * answer, all others run the vanilla body, and the staged list is already filtered and sorted.
 */
@Mixin(NearestLivingEntitySensor.class)
public abstract class PrtsNearestLivingReuseMixin {

    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void prts$useStagedNeighbours(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        if (!NativeNeighborStage.bound()) return;
        List<LivingEntity> staged = NativeNeighborStage.take();
        if (staged == null) return;
        Brain<?> brain = entity.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, staged);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
            new NearestVisibleLivingEntities(entity, staged));
        ci.cancel();
    }
}