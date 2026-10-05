/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the block entity ticks of one level, which is where a running machine's cost lands. The
 * call is not changed: the span is read around it and reported after it. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsSelfCosts;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(Level.class)
public abstract class PrtsBlockEntitySelfCostMixin {

    @Unique
    private long prts$blockEntitiesStartedAt;

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void prts$openBlockEntities(CallbackInfo ci) {
        if (PrtsSelfCosts.installed()) {
            prts$blockEntitiesStartedAt = System.nanoTime();
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"))
    private void prts$closeBlockEntities(CallbackInfo ci) {
        if (!PrtsSelfCosts.installed()) {
            return;
        }
        Level level = (Level) (Object) this;
        PrtsSelfCosts.blockEntities(level.dimension().location().toString(),
            System.nanoTime() - prts$blockEntitiesStartedAt);
    }
}
