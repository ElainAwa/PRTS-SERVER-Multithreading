/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The point-of-interest take/release bridge. Known limitation: the claim key comes from the search
 * centre passed to take, while the manager selects the actual record inside the call, so this hook
 * cannot make the real record authoritative; a refusal may also leave the caller's own state
 * untouched while the manager call is skipped. It is off in every shipped build; do not enable it
 * until the owner side runs the real operation on the authoritative thread and consumes the
 * manager's result. */
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
