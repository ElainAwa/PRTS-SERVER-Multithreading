/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.observation;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;
import io.izzel.arclight.common.prts.kernel.AiSensorCounters;
import io.izzel.arclight.common.prts.kernel.AiSensorValueBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Internal single-method carrier: same value core for the host path and a worker call. Off by
 * default; the vanilla predicate is untouched unless the private property is set. */
@Mixin(VillagerHostilesSensor.class)
public abstract class PrtsVillagerHostilesSensorMixin {

    @Shadow
    @Final
    private static ImmutableMap<EntityType<?>, Float> ACCEPTABLE_DISTANCE_FROM_HOSTILES;

    @Unique
    private boolean prts$matchingValues(String targetTypeId, double distanceSq) {
        ResourceLocation id = ResourceLocation.tryParse(targetTypeId);
        if (id == null) return false;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Float threshold = ACCEPTABLE_DISTANCE_FROM_HOSTILES.get(type);
        return threshold != null && distanceSq <= (double) threshold * (double) threshold;
    }

    @Inject(method = "isMatchingEntity", at = @At("HEAD"), cancellable = true)
    private void prts$takeOverMatching(LivingEntity attacker, LivingEntity target,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!AiSensorCounters.enabled()) return;
        AiSensorCounters.isMatchingCall();
        if (AiSensorValueBridge.fallbackMode()) {
            AiSensorCounters.hostFallback();
        }
        AiSensorCounters.mixinApplied();
        if (!Boolean.getBoolean(AiSensorCounters.CARRIER)) {
            AiSensorCounters.fallbackHost();
            return;
        }
        AiSensorCounters.coreHost();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        cir.setReturnValue(prts$matchingValues(id == null ? "" : id.toString(),
            target.distanceToSqr(attacker)));
    }
}
