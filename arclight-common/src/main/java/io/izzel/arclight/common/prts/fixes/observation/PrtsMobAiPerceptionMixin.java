/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Lets a non-player mob's frozen nearest-player perception run on the existing entity worker
 * pool; the server thread commits the answer at a later serverAiStep. The host own AI path runs
 * unchanged when no answer is ready or the answer is stale, so this only adds a candidate target
 * for a mob that still has none. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.decision.AiPerception;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Mob.class)
public abstract class PrtsMobAiPerceptionMixin {

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void prts$perceivePlayers(CallbackInfo ci) {
        AiPerception.beginServerAiStep((Mob) (Object) this);
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void prts$commitPerception(CallbackInfo ci) {
        AiPerception.endServerAiStep((Mob) (Object) this);
    }
}