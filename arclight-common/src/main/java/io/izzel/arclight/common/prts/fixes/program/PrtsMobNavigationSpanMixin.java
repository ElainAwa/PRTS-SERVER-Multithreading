/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Extracts the original navigation.tick() block of Mob.serverAiStep into NavigationSpanStage: the
 * instruction is replaced by one call to that stage, which itself runs the same virtual call. This
 * keeps a single implementation of the block for the host path and a later owner strategy, and it
 * changes no branch, no exception order and no profiler push/pop, because those stay in the host
 * frame around this call.
 */
package io.izzel.arclight.common.prts.fixes.program;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.NavigationSpanStage;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Behaviour: the navigation tick block of one mob server-AI step runs through its declared stage.
 * Why this point: the block is exactly that one virtual call inside the host method. Invariant: the
 * host frame, profiler pairs, locals and exception order are unchanged; the stage runs it once.
 * Failure semantics: the stage adds no wait, no fallback and no second execution. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Mob.class)
public abstract class PrtsMobNavigationSpanMixin {

    @Redirect(method = "serverAiStep",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"))
    private void prts$navigationSpan(PathNavigation navigation) {
        NavigationSpanStage.run(navigation);
    }
}
