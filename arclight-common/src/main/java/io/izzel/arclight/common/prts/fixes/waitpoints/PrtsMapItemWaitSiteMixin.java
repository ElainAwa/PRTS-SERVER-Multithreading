/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The held map redraw: it reads the blocks in the area the map covers, one chunk after another.
 *
 * The hook opens an observation at the head of the method and closes it at the return, and that is
 * all it does: no upper bound is read into a decision, no wait is shortened, delayed or cancelled,
 * and the call proceeds exactly as it did before. With no watcher installed both calls are a single
 * volatile read each.
 */
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
