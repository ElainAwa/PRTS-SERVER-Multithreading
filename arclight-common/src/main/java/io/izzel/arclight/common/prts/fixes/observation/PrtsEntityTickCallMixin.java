/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the calls of the original entity tick at the body itself, so a row ticked by any path is
 * seen, including a second run of one row. Read-only, never cancels or waits. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsHostTickCalls;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Entity.class)
public abstract class PrtsEntityTickCallMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void prts$noteHostTick(CallbackInfo ci) {
        if (PrtsHostTickCalls.ARMED) {
            PrtsHostTickCalls.note((Entity) (Object) this);
        }
    }
}
