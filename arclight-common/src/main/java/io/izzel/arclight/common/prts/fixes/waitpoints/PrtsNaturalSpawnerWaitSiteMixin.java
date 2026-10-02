/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the mob spawning call, which reaches a chunk by block position before it decides. */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(NaturalSpawner.class)
public abstract class PrtsNaturalSpawnerWaitSiteMixin {

    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"))
    private static void prts$openSpawnCategoryForPosition(MobCategory category, ServerLevel level,
                                                          BlockPos pos, CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY);
    }

    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
        at = @At("RETURN"))
    private static void prts$closeSpawnCategoryForPosition(MobCategory category, ServerLevel level,
                                                           BlockPos pos, CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY, level);
    }
}
