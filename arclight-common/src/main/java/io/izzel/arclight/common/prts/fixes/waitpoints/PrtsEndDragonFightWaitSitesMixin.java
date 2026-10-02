/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the three arena readiness questions the end dragon fight asks on every respawn stage. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(EndDragonFight.class)
public abstract class PrtsEndDragonFightWaitSitesMixin {

    @Inject(method = "hasActiveExitPortal", at = @At("HEAD"))
    private void prts$openHasActiveExitPortal(CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.END_DRAGON_FIGHT_HAS_ACTIVE_EXIT_PORTAL);
    }

    @Inject(method = "hasActiveExitPortal", at = @At("RETURN"))
    private void prts$closeHasActiveExitPortal(CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.end(PrtsWaitSites.END_DRAGON_FIGHT_HAS_ACTIVE_EXIT_PORTAL);
    }

    @Inject(method = "findExitPortal", at = @At("HEAD"))
    private void prts$openFindExitPortal(CallbackInfoReturnable<BlockPattern.BlockPatternMatch> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.END_DRAGON_FIGHT_FIND_EXIT_PORTAL);
    }

    @Inject(method = "findExitPortal", at = @At("RETURN"))
    private void prts$closeFindExitPortal(CallbackInfoReturnable<BlockPattern.BlockPatternMatch> cir) {
        PrtsWaitSites.end(PrtsWaitSites.END_DRAGON_FIGHT_FIND_EXIT_PORTAL);
    }

    @Inject(method = "isArenaLoaded", at = @At("HEAD"))
    private void prts$openIsArenaLoaded(CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.begin(PrtsWaitSites.END_DRAGON_FIGHT_IS_ARENA_LOADED);
    }

    @Inject(method = "isArenaLoaded", at = @At("RETURN"))
    private void prts$closeIsArenaLoaded(CallbackInfoReturnable<Boolean> cir) {
        PrtsWaitSites.end(PrtsWaitSites.END_DRAGON_FIGHT_IS_ARENA_LOADED);
    }
}
