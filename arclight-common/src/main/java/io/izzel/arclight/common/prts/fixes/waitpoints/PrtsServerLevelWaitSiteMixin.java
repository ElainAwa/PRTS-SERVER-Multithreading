/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the forced chunk command, which asks the chunk source for a full chunk. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerLevel.class)
public abstract class PrtsServerLevelWaitSiteMixin {

    @Inject(method = "setChunkForced(IIZ)Z", at = @At("HEAD"))
    private void prts$openSetChunkForced(int x, int z, boolean forced,
                                         CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED);
    }

    @Inject(method = "setChunkForced(IIZ)Z", at = @At("RETURN"))
    private void prts$closeSetChunkForced(int x, int z, boolean forced,
                                          CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.end(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED, this);
    }
}
