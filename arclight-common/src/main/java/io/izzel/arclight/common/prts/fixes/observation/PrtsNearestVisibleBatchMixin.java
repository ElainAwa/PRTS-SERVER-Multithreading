/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.common.prts.kernel.AiSensorCounters;
import io.izzel.arclight.common.prts.kernel.AiSensorValueBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.NearestVisibleLivingEntitySensor;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Mixin(NearestVisibleLivingEntitySensor.class)
public abstract class PrtsNearestVisibleBatchMixin {

    private static final java.util.concurrent.atomic.AtomicInteger WRITE_SAMPLES =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger CLEAR_SAMPLES =
        new java.util.concurrent.atomic.AtomicInteger();

    private static void sampleMemory(String kind, LivingEntity entity, java.util.Optional<LivingEntity> memory,
                                     long expectedId) {
        boolean write = "write".equals(kind);
        if ((write ? WRITE_SAMPLES : CLEAR_SAMPLES).getAndIncrement() >= 8) return;
        String target = memory.map(t -> t.getId() + ":" + t.getType().toString()).orElse("-");
        boolean match = !write || memory.map(t -> t.getId() == expectedId).orElse(false);
        System.out.println("[PRTS] ai-diag memory_sample kind=" + kind
            + " entity=" + entity.getId() + " present=" + memory.isPresent()
            + " expected=" + (write ? expectedId : "-") + " target=" + target
            + " match=" + match);
    }

    @Shadow
    protected abstract MemoryModuleType<LivingEntity> getMemory();

    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void prts$batchHostiles(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        if (!((Object) this instanceof VillagerHostilesSensor)) return;
        if (!Boolean.getBoolean("arclight.prts.aiSensorCore")) return;
        var visible = entity.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
        if (visible.isEmpty()) {
            AiSensorCounters.batchNoVisible();
            entity.getBrain().setMemory(getMemory(), Optional.empty());
            sampleMemory("clear", entity, entity.getBrain().getMemory(getMemory()), -1L);
            AiSensorCounters.batchCancel();
            ci.cancel();
            return;
        }
        List<LivingEntity> candidates = new ArrayList<>();
        visible.get().find(candidate -> true).forEach(candidates::add);
        if (candidates.isEmpty()) {
            AiSensorCounters.batchEmpty();
            entity.getBrain().setMemory(getMemory(), Optional.empty());
            AiSensorCounters.batchCancel();
            ci.cancel();
            return;
        }
        String[] typeIds = new String[candidates.size()];
        double[] distanceSq = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            LivingEntity target = candidates.get(i);
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            typeIds[i] = id == null ? "" : id.toString();
            distanceSq[i] = target.distanceToSqr(entity);
        }
        AiSensorCounters.batchStarted();
        boolean[] answers = AiSensorValueBridge.matchingBatch(typeIds, distanceSq);
        if (answers == null) {
            AiSensorCounters.workerFallback();
            return;
        }
        AiSensorCounters.batchCancel();
        AiSensorCounters.workerSuccess(answers.length);
        AiSensorCounters.hostNotRepeated(answers.length);
        for (int i = 0; i < answers.length; i++) {
            if (answers[i]) {
                LivingEntity selected = candidates.get(i);
                entity.getBrain().setMemory(getMemory(), Optional.of(selected));
                sampleMemory("write", entity, entity.getBrain().getMemory(getMemory()), selected.getId());
                AiSensorCounters.memoryWrite();
                AiSensorCounters.coreHost();
                ci.cancel();
                return;
            }
        }
        entity.getBrain().setMemory(getMemory(), Optional.empty());
        sampleMemory("clear", entity, entity.getBrain().getMemory(getMemory()), -1L);
        AiSensorCounters.memoryClear();
        ci.cancel();
    }
}
