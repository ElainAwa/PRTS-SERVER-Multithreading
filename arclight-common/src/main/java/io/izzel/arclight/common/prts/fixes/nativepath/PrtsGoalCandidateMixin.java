/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeGoalStage;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Behaviour: when the wave prefiltered this row's goal candidates, findClosest scans only that
 * small set. Why: the distance-and-type predicate already ran in the parallel wave; rescanning the
 * full neighbour list on the seat thread is the hot cost. Point: the findClosest entry.
 * Invariant: the line-of-sight test is unchanged and still applied to every candidate, and a row
 * without a staged set runs the vanilla body.
 */
@Mixin(NearestVisibleLivingEntities.class)
public abstract class PrtsGoalCandidateMixin {

    @Inject(method = "findClosest", at = @At("HEAD"), cancellable = true)
    private void prts$usePrefilteredCandidates(Predicate<LivingEntity> predicate,
                                               CallbackInfoReturnable<Optional<LivingEntity>> cir) {
        if (!NativeGoalStage.bound()) return;
        List<LivingEntity> candidates = NativeGoalStage.take();
        if (candidates == null) return;
        // Keep the exact vanilla line-of-sight behaviour on the staged candidates.
        NearestVisibleLivingEntities self = (NearestVisibleLivingEntities) (Object) this;
        for (LivingEntity candidate : candidates) {
            if (predicate.test(candidate) && self.contains(candidate)) {
                cir.setReturnValue(Optional.of(candidate));
                return;
            }
        }
        cir.setReturnValue(Optional.empty());
    }
}