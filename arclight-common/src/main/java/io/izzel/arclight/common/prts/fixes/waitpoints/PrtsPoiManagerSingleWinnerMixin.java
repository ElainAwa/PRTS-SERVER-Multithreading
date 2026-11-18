/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The POI take/release single winner: the tick thread hands the current typed update to the
 * PoiManager entry and a losing claim is refused before the host take/release runs. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.izzel.arclight.common.prts.kernel.domain.entity.interaction.PoiSingleWinnerWiring;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

@Mixin(PoiManager.class)
public abstract class PrtsPoiManagerSingleWinnerMixin {

    /** Behaviour: a refused take returns empty before the vanilla take runs. Why here: the take
     * result is decided exactly once at this owner semantic point. Invariant: one record has one
     * winner; a missing context falls back to the host path unchanged. */
    @Inject(method = "take", at = @At("HEAD"), cancellable = true)
    private void prts$refuseLosingTake(Predicate<Holder<PoiType>> type,
                                       BiPredicate<Holder<PoiType>, BlockPos> position,
                                       BlockPos pos, int radius,
                                       CallbackInfoReturnable<Optional<BlockPos>> cir) {
        if (PoiSingleWinnerWiring.denyTake(pos)) {
            cir.setReturnValue(Optional.empty());
        }
    }

    /** Behaviour: a non-holder release returns false before the vanilla release runs. Why here: the
     * release is the only place the holder is checked before the effect. Invariant: release only
     * the holder; a missing context falls back to the host path unchanged. */
    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void prts$refuseWrongHolderRelease(BlockPos pos,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (PoiSingleWinnerWiring.denyRelease(pos)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
