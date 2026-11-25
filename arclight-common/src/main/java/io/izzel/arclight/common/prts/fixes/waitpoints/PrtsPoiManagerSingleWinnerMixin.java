/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The point-of-interest take/release observation. Behaviour: after the manager's own take or
 * release returns, the bridge records the record the manager actually used; it never cancels the
 * call. Why here: the returned record is the only value the world used, so the observation must
 * read that return value at this point. Invariant: a failed take or a refused release records
 * nothing, a call without a bound owner context records nothing, and the host path is unchanged. */
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

    /** Behaviour: records the record the real take returned, never cancels the take. Why here: the
     * return value is decided exactly at this point and is the record the world uses. Invariant:
     * an empty result records nothing and a call without a bound owner context records nothing. */
    @Inject(method = "take", at = @At("RETURN"))
    private void prts$observeRealTake(Predicate<Holder<PoiType>> type,
                                      BiPredicate<Holder<PoiType>, BlockPos> position,
                                      BlockPos pos, int radius,
                                      CallbackInfoReturnable<Optional<BlockPos>> cir) {
        PoiSingleWinnerWiring.noteTakeResult(pos, cir.getReturnValue());
    }

    /** Behaviour: records the record the real release named, never cancels the release. Why here:
     * only the manager knows which record its release call ended and whether it released it.
     * Invariant: a refused release records nothing and a call without a bound context records
     * nothing. */
    @Inject(method = "release", at = @At("RETURN"))
    private void prts$observeRealRelease(BlockPos pos,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() != null) {
            PoiSingleWinnerWiring.noteReleaseResult(pos, cir.getReturnValue());
        }
    }
}
