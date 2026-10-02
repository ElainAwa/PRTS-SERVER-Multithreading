/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the held map redraw, which reads the blocks the map covers one chunk after another. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(MapItem.class)
public abstract class PrtsMapItemWaitSiteMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void prts$openUpdate(Level level, Entity entity, MapItemSavedData data,
                                 CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.MAP_ITEM_UPDATE);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void prts$closeUpdate(Level level, Entity entity, MapItemSavedData data,
                                  CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.MAP_ITEM_UPDATE, level);
    }
}
