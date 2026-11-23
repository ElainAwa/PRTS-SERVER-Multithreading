/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.owner;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerBodyMarker;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Supplier;

/**
 * Behaviour: a goal selector tick from an opt-in parallel owner body uses the inactive profiler;
 * every other thread keeps the captured supplier. Why here: the goal selector stores the supplier
 * from its constructor, so replacing Level.getProfiler does not reach it. Point: the two supplier
 * reads of the goal ticks. Invariant: only the profiler value changes; goal order and results do not;
 * a thread that never ran an owner body takes the original supplier.
 */
@Mixin(GoalSelector.class)
public abstract class PrtsOwnerGoalProfilerMixin {

    @WrapOperation(method = {"tick", "tickRunningGoals"},
        at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;"))
    private ProfilerFiller prts$ownerGoalProfiler(Supplier<ProfilerFiller> supplier,
                                                   Operation<ProfilerFiller> original) {
        return OwnerBodyMarker.active() ? InactiveProfiler.INSTANCE : original.call(supplier);
    }
}
