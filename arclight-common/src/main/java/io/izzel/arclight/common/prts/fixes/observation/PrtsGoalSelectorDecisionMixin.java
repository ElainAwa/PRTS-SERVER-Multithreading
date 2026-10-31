/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Answers false to WrappedGoal.canUse while the enclosing goal-selector tick is deferred; the
 * cleanup and running-goal portions of GoalSelector.tick are not intercepted. */
package io.izzel.arclight.common.prts.fixes.observation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.decision.B1DecisionGate;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(GoalSelector.class)
public abstract class PrtsGoalSelectorDecisionMixin {

    @WrapOperation(method = "tick", at = @org.spongepowered.asm.mixin.injection.At(
        value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/WrappedGoal;canUse()Z"))
    private boolean prts$deferCanUse(WrappedGoal goal, Operation<Boolean> original) {
        if (B1DecisionGate.suppressed()) {
            return false;
        }
        return original.call(goal);
    }
}
