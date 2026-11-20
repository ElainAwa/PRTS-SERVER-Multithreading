/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.program;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.NavigationSpanStage;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Behaviour: the host runs the extracted navigation stage through one entry, so the host method
 * and any later owner driver share one implementation. The host frame and the successor stay
 * unchanged; the stage runs on the calling thread and never starts a worker. */
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
