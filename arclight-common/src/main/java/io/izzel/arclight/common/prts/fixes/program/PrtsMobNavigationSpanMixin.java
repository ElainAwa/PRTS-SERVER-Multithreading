/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.program;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.NavigationSpanStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerNativeProgram;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.ServerThreadOwnerPhase;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Behaviour: the declared navigation span may be claimed by the opt-in native program path; when
 * it is not claimed the host runs the same stage once. The host frame and the successor stay
 * unchanged. Failure semantics: a claimed span is begun before the worker runs, and a failure is
 * terminal after it entered; it never falls back to a second inline span. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Mob.class)
public abstract class PrtsMobNavigationSpanMixin {

    @Redirect(method = "serverAiStep",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V"))
    private void prts$navigationSpan(PathNavigation navigation) {
        if (OwnerNativeProgram.enabled()
            && ServerThreadOwnerPhase.runNativeProgramSpan((Mob) (Object) this, navigation)) {
            return;
        }
        NavigationSpanStage.run(navigation);
    }
}
