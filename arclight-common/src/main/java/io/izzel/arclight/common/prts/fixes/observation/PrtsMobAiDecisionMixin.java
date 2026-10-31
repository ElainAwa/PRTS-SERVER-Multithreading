/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Defers only the new-goal selection of the live goal selector while the B1 gate is armed: the
 * tick itself still runs its cleanup and running goals. It is off unless degrade-actions is on and
 * the gate was armed by a real overrun; no readout key is published. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.decision.B1DecisionGate;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Mob.class)
public abstract class PrtsMobAiDecisionMixin {

    @Shadow
    @Final
    protected GoalSelector goalSelector;

    @Unique
    private long prts$aiStartedAt;

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void prts$openAiDecision(CallbackInfo ci) {
        prts$aiStartedAt = System.nanoTime();
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void prts$closeAiDecision(CallbackInfo ci) {
        Mob mob = (Mob) (Object) this;
        SelfTimers.note(SelfClass.AI, mob.level().dimension().location().toString(),
            mob.getClass().getName(), System.nanoTime() - prts$aiStartedAt);
    }

    @WrapOperation(method = "serverAiStep", at = @org.spongepowered.asm.mixin.injection.At(
        value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void prts$deferGoalSelection(GoalSelector selector, Operation<Void> original) {
        Mob mob = (Mob) (Object) this;
        if (selector != goalSelector || !KernelSettings.degradeActions()) {
            original.call(selector);
            return;
        }
        String worldId = mob.level().dimension().location().toString();
        boolean brainActive = mob.getBrain() != null
            && !mob.getBrain().getActiveActivities().isEmpty();
        boolean eligible = B1DecisionGate.eligible(brainActive,
            !goalSelector.getAvailableGoals().isEmpty());
        B1DecisionGate.Decision decision = B1DecisionGate.decide(worldId, mob.getUUID(), eligible);
        if (!decision.suppress()) {
            original.call(selector);
            return;
        }
        B1DecisionGate.beginSuppress();
        try {
            original.call(selector);
        } finally {
            B1DecisionGate.endSuppress();
        }
        if (decision.firstSkip() && decision.token() > 0L) {
            KernelModule.instance().noteB1Applied(decision.token());
        }
    }
}
